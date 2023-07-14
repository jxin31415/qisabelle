package server

import util.control.Breaks
import scala.collection.mutable.ListBuffer
import scala.concurrent.{
  Await,
  ExecutionContext,
  Future,
  TimeoutException,
  blocking
}
import scala.concurrent.duration.Duration
import scala.util.{Success, Failure}
import sys.process._
import _root_.java.nio.file.{Files, Path}
import _root_.java.io.File

import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.mlvalue.{
  AdHocConverter,
  MLFunction,
  MLFunction0,
  MLFunction2,
  MLFunction3,
  MLFunction4,
  MLValue,
  MLValueWrapper
}
import de.unruh.isabelle.mlvalue.MLValue.{
  compileFunction,
  compileFunction0,
  compileValue
}
import de.unruh.isabelle.pure.{
  Context,
  Position,
  Theory,
  TheoryHeader,
  ToplevelState
}

// Implicits
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.control.IsabelleMLException

object Transition extends AdHocConverter("Toplevel.transition")
object ProofState extends AdHocConverter("Proof.state")
object RuntimeError extends AdHocConverter("Runtime.error")
object Pretty extends AdHocConverter("Pretty.T")
object ProofContext extends AdHocConverter("Proof_Context.T")

case class TheoryText(text: String, path: Path)(implicit isabelle: Isabelle, ec: scala.concurrent.ExecutionContext) {
  // val text = text
  // val path = path
  val position: Position = Position.none
}

class MiniPisaOS(
    var path_to_isa_bin: String,
    var path_to_file: String,
    var working_directory: String,
    var use_Sledgehammer: Boolean = false,
    var debug: Boolean = false
) {
  if (debug) println("Checkpoint 1")
  val currentTheoryName: String = path_to_file.split("/").last.replace(".thy", "")
  val currentProjectName: String = {
    if (path_to_file.contains("afp")) {
      working_directory
        .slice(working_directory.indexOf("thys/") + 5, working_directory.length)
        .split("/")
        .head
    } else if (path_to_file.contains("Isabelle") && path_to_file.contains("/src/")) {
      // The theory file could be /Applications/Isabelle2021.app/Isabelle/src/HOL/Analysis/ex
      // The correct project name for it is HOL-Analysis-ex
      val relative_working_directory =
        working_directory
          .slice(
            working_directory.indexOf("/src/") + 5,
            working_directory.length
          )
          .split("/")
      relative_working_directory.mkString("-")
    } else if (path_to_file.contains("miniF2F")) {
      //      working_directory.split("/").last
      "HOL"
    } else {
      throw new Exception("Unsupported...")
    }
  }
  if (debug) println("Checkpoint 2")
  // Figure out the session roots information and import the correct libraries
  val sessionRoots: Seq[Path] = {
    if (path_to_file.contains("afp")) {
      Seq(
        Path.of(
          working_directory.slice(-1, working_directory.indexOf("thys/") + 4)
        )
      )
    } else if (
      path_to_file.contains("Isabelle") && path_to_file.contains("/src/")
    ) {
      val src_index: Int = working_directory.indexOf("/src/") + 5
      val session_root_path_string: String =
        working_directory.slice(0, src_index) +
          working_directory
            .slice(src_index, working_directory.length)
            .split("/")
            .head
      Seq(Path.of(session_root_path_string))
    } else if (path_to_file.contains("miniF2F")) {
      Seq()
    } else {
      Seq(Path.of("This is not supported at the moment"))
    }
  }
  if (debug) println("Checkpoint 3")
  // Prepare setup config and the implicit Isabelle context
  val setup: Isabelle.Setup = Isabelle.Setup(
    isabelleHome = Path.of(path_to_isa_bin),
    sessionRoots = sessionRoots,
    userDir = None,
    logic = currentProjectName,
    workingDirectory = Path.of(working_directory),
    build = false
  )
  implicit val isabelle: Isabelle = new Isabelle(setup)
  implicit val ec: ExecutionContext = ExecutionContext.global
  if (debug) println("Checkpoint 4 (the slow one)")

  // Compile useful ML functions
  val init_toplevel: MLFunction0[ToplevelState] =
    compileFunction0[ToplevelState]("Toplevel.init_toplevel")
  val proof_level: MLFunction[ToplevelState, Int] =
    compileFunction[ToplevelState, Int]("Toplevel.level")
  val command_exception
      : MLFunction3[Boolean, Transition.T, ToplevelState, ToplevelState] =
    compileFunction[Boolean, Transition.T, ToplevelState, ToplevelState](
      "fn (int, tr, st) => Toplevel.command_exception int tr st"
    )
  val command_exception_with_10s_timeout
      : MLFunction3[Boolean, Transition.T, ToplevelState, ToplevelState] =
    compileFunction[Boolean, Transition.T, ToplevelState, ToplevelState](
      """fn (int, tr, st) => let
        |  fun go_run (a, b, c) = Toplevel.command_exception a b c
        |  in Timeout.apply (Time.fromSeconds 10) go_run (int, tr, st) end""".stripMargin
    )
  val command_exception_with_30s_timeout
      : MLFunction3[Boolean, Transition.T, ToplevelState, ToplevelState] =
    compileFunction[Boolean, Transition.T, ToplevelState, ToplevelState](
      """fn (int, tr, st) => let
        |  fun go_run (a, b, c) = Toplevel.command_exception a b c
        |  in Timeout.apply (Time.fromSeconds 30) go_run (int, tr, st) end""".stripMargin
    )

  val parse_text: MLFunction2[Theory, String, List[(Transition.T, String)]] =
    compileFunction[Theory, String, List[(Transition.T, String)]]("""fn (thy, text) => let
        |  val transitions = Outer_Syntax.parse_text thy (K thy) Position.start text
        |  fun addtext symbols [tr] =
        |        [(tr, implode symbols)]
        |    | addtext _ [] = []
        |    | addtext symbols (tr::nextTr::trs) = let
        |        val (this,rest) = Library.chop (Position.distance_of (Toplevel.pos_of tr, Toplevel.pos_of nextTr) |> Option.valOf) symbols
        |        in (tr, implode this) :: addtext rest (nextTr::trs) end
        |  in addtext (Symbol.explode text) transitions end""".stripMargin)
  val toplevel_string_of_state: MLFunction[ToplevelState, String] =
    compileFunction[ToplevelState, String](
      "fn (s) => YXML.content_of (Toplevel.string_of_state s)"
    )

  if (debug) println("Checkpoint 4.5")
  val header_read: MLFunction2[String, Position, TheoryHeader] =
    compileFunction[String, Position, TheoryHeader]("fn (text, pos) => Thy_Header.read pos text")
  val begin_theory =
    compileFunction[Path, TheoryHeader, List[Theory], Theory]("fn (path, header, parents) => Resources.begin_theory path header parents")

  def beginTheory(
      source: TheoryText
  )(implicit isabelle: Isabelle, ec: ExecutionContext): Theory = {
    if (debug) println("Checkpoint 9_1")
    val header = header_read(source.text, source.position).retrieveNow
    if (debug) println("Checkpoint 9_3")
    val registers: ListBuffer[String] = new ListBuffer[String]()
    if (debug) println("Checkpoint 9_4")
    for (theory_name <- header.imports) {
      // var treated_name = theory_name.trim
      // if (treated_name.startsWith("..")) {
      //   val filedir_chunks = path_to_file.split("/").dropRight(1)
      //   var imported_dir_chunks = filedir_chunks
      //   var treated_chunks = treated_name.split("/")
      //   while (treated_chunks.head == "..") {
      //     imported_dir_chunks = imported_dir_chunks.dropRight(1)
      //     treated_chunks = treated_chunks.drop(1)
      //   }
      //   imported_dir_chunks = imported_dir_chunks ++ treated_chunks
      //   imported_dir_chunks = imported_dir_chunks.dropWhile(_!=currentProjectName)
      //   val imported_dir = imported_dir_chunks.mkString(".")
      //   registers += imported_dir
      // } else
      if (importMap.contains(theory_name)) {
        registers += s"${currentProjectName}.${importMap(theory_name)}"
      } else registers += theory_name
    }
    if (debug) println("Checkpoint 9_5")

    if (debug) {
      println(source.path)
      println(header)
      println(registers.toList)
    }

    begin_theory(source.path, header, registers.toList.map(Theory.apply)).force.retrieveNow
  }
  if (debug) println("Checkpoint 5")

  // Find out about the starter string
  private val fileContent: String = Files.readString(Path.of(path_to_file))
  val fileContentCopy: String = fileContent
  if (debug) println("Checkpoint 6")
  private def getStarterString: String = {
    val decoyThy: Theory = Theory("Main")
    for (
      (transition, text) <- parse_text(decoyThy, fileContent).force.retrieveNow
    ) {
      if (
        text.contains("theory") && text.contains(currentTheoryName) && text
          .contains("begin")
      ) {
        return text
      }
    }
    throw new Exception("Could not find theory dependencies.")
  }
  if (debug) println("Checkpoint 7")
  val starter_string: String = getStarterString.trim.replaceAll("\n", " ").trim

  // Find out what to import from the current directory
  def getListOfTheoryFiles(dir: File): List[File] = {
    if (dir.exists && dir.isDirectory) {
      var listOfFilesBuffer: ListBuffer[File] = new ListBuffer[File]
      for (f <- dir.listFiles()) {
        if (f.isDirectory) {
          listOfFilesBuffer = listOfFilesBuffer ++ getListOfTheoryFiles(f)
        } else if (f.toString.endsWith(".thy")) {
          listOfFilesBuffer += f
        }
      }
      listOfFilesBuffer.toList
    } else {
      List[File]()
    }
  }

  def sanitiseInDirectoryName(fileName: String): String = {
    fileName.replace("\"", "").split("/").last.split(".thy").head
  }
  if (debug) println("Checkpoint 8")
  // Figure out what theories to import
  val available_files: List[File] = getListOfTheoryFiles(
    new File(working_directory)
  )
  var available_imports_buffer: ListBuffer[String] = new ListBuffer[String]
  for (file_name <- available_files) {
    if (file_name.getName().endsWith(".thy")) {
      available_imports_buffer =
        available_imports_buffer += file_name.getName().split(".thy")(0)
    }
  }
  var available_imports: Set[String] = available_imports_buffer.toSet
  val theoryNames: List[String] = starter_string
    .split("imports")(1)
    .split("begin")(0)
    .split(" ")
    .map(_.trim)
    .filter(_.nonEmpty)
    .toList
  var importMap: Map[String, String] = Map()
  for (theory_name <- theoryNames) {
    val sanitisedName = sanitiseInDirectoryName(theory_name)
    if (available_imports(sanitisedName)) {
      importMap += (theory_name.replace("\"", "") -> sanitisedName)
    }
  }

  var top_level_state_map: Map[String, MLValue[ToplevelState]] = Map()
  val theoryStarter: TheoryText = TheoryText(starter_string, setup.workingDirectory.resolve(""))
  if (debug) println("Checkpoint 9")
  var thy1: Theory = beginTheory(theoryStarter)
  if (debug) println("Checkpoint 9_6")
  thy1.await
  if (debug) println("Checkpoint 10")

  // setting up Sledgehammer
  // val thy_for_sledgehammer: Theory = Theory("HOL.List")
  val thy_for_sledgehammer = thy1
  val Sledgehammer: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer")
  val Sledgehammer_Commands: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Commands")
  val Sledgehammer_Prover: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Prover")

  // prove_with_Sledgehammer is mostly identical to check_with_Sledgehammer except for that when the returned Boolean is true, it will
  // also return a non-empty list of Strings, each of which contains executable commands to close the top subgoal. We might need to chop part of
  // the string to get the actual tactic. For example, one of the string may look like "Try this: by blast (0.5 ms)".
  if (debug) println("Checkpoint 11")
  val normal_with_Sledgehammer: MLFunction4[ToplevelState, Theory, List[
    String
  ], List[String], (Boolean, (String, List[String]))] =
    compileFunction[ToplevelState, Theory, List[String], List[
      String
    ], (Boolean, (String, List[String]))](
      s""" fn (state, thy, adds, dels) =>
            |    let
            |       fun get_refs_and_token_lists (name) = (Facts.named name, []);
            |       val adds_refs_and_token_lists = map get_refs_and_token_lists adds;
            |       val dels_refs_and_token_lists = map get_refs_and_token_lists dels;
            |       val override = {add=adds_refs_and_token_lists,del=dels_refs_and_token_lists,only=false};
            |       fun go_run (state, thy) =
            |          let
            |             val p_state = Toplevel.proof_of state;
            |             val ctxt = Proof.context_of p_state;
            |             val params = ${Sledgehammer_Commands}.default_params thy
            |                [("provers", "cvc5 vampire verit e spass z3 zipperposition"),("timeout","30"),("verbose","true")];
            |             val results = ${Sledgehammer}.run_sledgehammer params ${Sledgehammer_Prover}.Normal NONE 1 override p_state;
            |             val (result, (outcome, step)) = results;
            |           in
            |             (result, (${Sledgehammer}.short_string_of_sledgehammer_outcome outcome, [YXML.content_of step]))
            |           end;
            |    in
            |      Timeout.apply (Time.fromSeconds 35) go_run (state, thy) end
            |""".stripMargin
    )

  var toplevel: ToplevelState = init_toplevel().force.retrieveNow
  if (debug) println("Checkpoint 12")

  def reset_map(): Unit = {
    top_level_state_map = Map()
  }

  def reset_prob(): Unit = {
    thy1 = beginTheory(theoryStarter)
    toplevel = init_toplevel().force.retrieveNow
    reset_map()
  }

  def getStateString(top_level_state: ToplevelState): String =
    toplevel_string_of_state(top_level_state).force.retrieveNow

  def getProofLevel(top_level_state: ToplevelState): Int =
    proof_level(top_level_state).retrieveNow

  def singleTransitionWith10sTimeout(
      single_transition: Transition.T,
      top_level_state: ToplevelState
  ): ToplevelState = {
    command_exception_with_10s_timeout(
      true,
      single_transition,
      top_level_state
    ).retrieveNow.force
  }

  def singleTransitionWith30sTimeout(
      single_transition: Transition.T,
      top_level_state: ToplevelState
  ): ToplevelState = {
    command_exception_with_30s_timeout(
      true,
      single_transition,
      top_level_state
    ).retrieveNow.force
  }

  def singleTransition(
      single_transition: Transition.T,
      top_level_state: ToplevelState
  ): ToplevelState = {
    command_exception(
      true,
      single_transition,
      top_level_state
    ).retrieveNow.force
  }

  def singleTransition(singTransition: Transition.T): String = {
    //    TODO: include global facts
    toplevel = singleTransition(singTransition, toplevel)
    getStateString(toplevel)
  }

  @throws(classOf[IsabelleMLException])
  @throws(classOf[TimeoutException])
  def step(
      isar_string: String,
      top_level_state: ToplevelState,
      timeout_in_millis: Int = 2000
  ): ToplevelState = {
    if (debug) println("Begin step")
    // Normal isabelle business
    var tls_to_return: ToplevelState = clone_tls_scala(top_level_state)
    var stateString: String = ""
    val continue = new Breaks
    if (debug) println("Starting to step")
    val f_st = Future.apply {
      blocking {
        Breaks.breakable {
          if (debug) println("start parsing")
          for (
            (transition, text) <- parse_text(
              thy1,
              isar_string
            ).force.retrieveNow
          )
            continue.breakable {
              if (text.trim.isEmpty) continue.break()
              // println("Small step : " + text)
              tls_to_return = if (timeout_in_millis > 10000) {
                singleTransitionWith30sTimeout(transition, tls_to_return)
              } else singleTransitionWith10sTimeout(transition, tls_to_return)
              // println("Applied transition successfully")
            }
        }
      }
      "success"
    }
    if (debug) println("inter")

    // Await for infinite amount of time
    Await.result(f_st, Duration.Inf)
    if (debug) println(f_st)
    tls_to_return
  }

  def exit(): String = {
    isabelle.destroy()
    return "Destroyed"
  }

  def normal_with_hammer(
      top_level_state: ToplevelState,
      added_names: List[String],
      deleted_names: List[String],
      timeout_in_millis: Int = 35000
  ): (Boolean, List[String]) = {
    val f_res: Future[(Boolean, List[String])] = Future.apply {
      val first_result = normal_with_Sledgehammer(
        top_level_state,
        thy1,
        added_names,
        deleted_names
      ).force.retrieveNow
      (first_result._1, first_result._2._2)
    }
    Await.result(f_res, Duration(timeout_in_millis, "millis"))
  }

  def step_to_transition_text(
      isar_string: String,
      after: Boolean = true
  ): String = {
    var stateString: String = ""
    val continue = new Breaks
    if (debug) println("step_to_transition_text first iteration")
    Breaks.breakable {
      for (
        (transition, text) <- parse_text(thy1, fileContent).force.retrieveNow
      ) {
        continue.breakable {
          // println("transition=", transition)
          // println("text=", text)
          if (text.trim.isEmpty) continue.break()
          val trimmed_text =
            text.trim.replaceAll("\n", " ").replaceAll(" +", " ")
          if (trimmed_text == isar_string) {
            if (after) {
              if (debug) println("step_to_transition_text last transition")
              stateString = singleTransition(transition)
            }
            return stateString
          }
          stateString = singleTransition(transition)
        }
      }
    }
    println("Did not find the text")
    // stateString
    "error: Did not find the text"
  }

  // Manage top level states with the internal map
  def copy_tls: MLValue[ToplevelState] = toplevel.mlValue

  def clone_tls(tls_name: String): Unit =
    top_level_state_map += (tls_name -> copy_tls)

  def clone_tls(old_name: String, new_name: String): Unit =
    top_level_state_map += (new_name -> top_level_state_map(old_name))

  def _clone_tls_scala(tls_scala: ToplevelState): Future[ToplevelState] =
    ToplevelState.converter.retrieve(tls_scala.mlValue)

  def clone_tls_scala(tls_scala: ToplevelState): ToplevelState =
    Await.result(_clone_tls_scala(tls_scala), Duration.Inf)

  def register_tls(name: String, tls: ToplevelState): Unit =
    top_level_state_map += (name -> tls.mlValue)

  def _retrieve_tls(tls_name: String): Future[ToplevelState] =
    ToplevelState.converter.retrieve(top_level_state_map(tls_name))

  def retrieve_tls(tls_name: String): ToplevelState =
    Await.result(_retrieve_tls(tls_name), Duration.Inf)
}