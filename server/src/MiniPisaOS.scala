package server

import util.control.Breaks
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException, blocking}
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
import de.unruh.isabelle.mlvalue.MLValue.{compileFunction, compileFunction0, compileValue}
import de.unruh.isabelle.pure.{Context, Position, Theory, TheoryHeader, ToplevelState}

// Implicits
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.control.IsabelleMLException

object Transition   extends AdHocConverter("Toplevel.transition")
object ProofState   extends AdHocConverter("Proof.state")
object RuntimeError extends AdHocConverter("Runtime.error")
object Pretty       extends AdHocConverter("Pretty.T")
object ProofContext extends AdHocConverter("Proof_Context.T")

case class TheoryText(text: String, path: Path)(implicit
    isabelle: Isabelle,
    ec: scala.concurrent.ExecutionContext
) {
  // val text = text
  // val path = path
  val position: Position = Position.none
}

class MiniPisaOS(
    var path_to_isa_bin: os.Path, // Path to the Isabelle distribution directory (should contain bin/isabelle).
    var path_to_file: os.Path,
    var working_dir: os.Path,
    var use_Sledgehammer: Boolean = false,
    var debug: Boolean = false
) {
  // Folder name of the current project.
  val sessionName: String = {
    if (path_to_file.toString().contains("afp")) {
      working_dir.getSegment(working_dir.segments.indexOf("thys") + 1)
    } else if (
      path_to_file.toString().contains("Isabelle") && path_to_file.segments.contains("src")
    ) {
      working_dir.segments.drop(working_dir.segments.indexOf("thys") + 1).mkString("-")
    } else if (path_to_file.toString().contains("miniF2F")) {
      "HOL" // working_directory.last
    } else {
      throw new Exception("Unsupported file path:" + path_to_file.toString())
    }
  }

  // Figure out the session roots information and import the correct libraries
  def getPathPrefix(path: os.Path, n_segments: Int): os.Path = {
    os.Path("/" + path.segments.take(n_segments).mkString("/"))
  }
  val sessionRoots: Seq[os.Path] = {
    if (path_to_file.toString().contains("afp")) {
      Seq(getPathPrefix(working_dir, working_dir.segments.indexOf("thys") + 1))
    } else if (
      path_to_file.toString().contains("Isabelle") && path_to_file.segments.contains("src")
    ) {
      Seq(getPathPrefix(working_dir, working_dir.segments.indexOf("src") + 2))
    } else if (path_to_file.toString().contains("miniF2F")) {
      Seq()
    } else {
      throw new Exception("Unsupported file path:" + path_to_file.toString())
    }
  }
  if (debug) {
    println("sessionName: " + sessionName)
    println("sessionRoots: " + sessionRoots)
    println("path_to_isa_bin: " + path_to_isa_bin)
    println("working_dir: " + working_dir)
  }

  // Start the actual Isabelle process.
  val setup: Isabelle.Setup = Isabelle.Setup(
    isabelleHome = path_to_isa_bin.toNIO,
    // Additional session directories in which Isabelle will search for sessions;
    // these are passed as '-d' options to the 'isabelle build' process:
    sessionRoots = sessionRoots.map(_.toNIO),
    // Use default '.isabelle' dir to store heaps in:
    userDir = None,
    // Session whose heap image (a state cache) we load in Isabelle.
    // If I just use 'HOL', Resources.begin_theory takes a long time.
    // Loading a heap after the process started seems to be impossible,
    // see Isabelle/src/Pure/ML/ml_process.scala.
    logic = sessionName,
    workingDirectory = working_dir.toNIO,
    build = false
  )
  implicit val isabelle: Isabelle   = new Isabelle(setup)
  implicit val ec: ExecutionContext = ExecutionContext.global
  if (debug) println("Isabelle constructed.")

  // Compile useful ML functions
  val init_toplevel = compileFunction0[ToplevelState]("Toplevel.init_toplevel")
  if (debug) println("Checkpoint 4.1")
  val proof_level = compileFunction[ToplevelState, Int]("Toplevel.level")
  if (debug) println("Checkpoint 4.2 (the slow one)")
  // We could maybe speed that up by putting it in the heap?
  val command_exception = compileFunction[Boolean, Transition.T, ToplevelState, ToplevelState](
    "fn (int, tr, st) => Toplevel.command_exception int tr st"
  )
  if (debug) println("Checkpoint 4.3")
  val command_exception_with_timeout =
    compileFunction[Int, Boolean, Transition.T, ToplevelState, ToplevelState](
      """fn (timeout, int, tr, st) =>
        |  Timeout.apply (Time.fromMilliseconds timeout) Toplevel.command_exception int tr st
      """.stripMargin
    )
  if (debug) println("Checkpoint 4.4")

  // Calls Outer_Syntax.parse_text and pairs each transition with the text that it corresponds to.
  // Args:
  // - thy: theory to use as context? or to work in adding stuff to it?
  // - text: the text to parse
  // Returns: list of pairs (transition, text)
  val parse_text = compileFunction[Theory, String, List[(Transition.T, String)]](
    """fn (thy, text) => let
      |  val transitions = Outer_Syntax.parse_text thy (K thy) Position.start text
      |  fun addtext symbols [tr]              = [(tr, implode symbols)]
      |    | addtext _       []                = []
      |    | addtext symbols (tr::nextTr::trs) = let
      |        val (this,rest) = Library.chop (Position.distance_of (Toplevel.pos_of tr, Toplevel.pos_of nextTr) |> Option.valOf) symbols
      |        in (tr, implode this) :: addtext rest (nextTr::trs) end
      |  in addtext (Symbol.explode text) transitions end
    """.stripMargin
  )

  val get_transition_name     = compileFunction[Transition.T, String]("Toplevel.name_of")
  val is_transition_init      = compileFunction[Transition.T, Boolean]("Toplevel.is_init")
  val is_transition_malformed = compileFunction[Transition.T, Boolean]("Toplevel.is_malformed")
  val is_end_theory           = compileFunction[ToplevelState, Boolean]("Toplevel.is_end_theory")
  val toplevel_string_of_state = compileFunction[ToplevelState, String](
    "fn (s) => YXML.content_of (Toplevel.string_of_state s)"
  )
  val header_read = compileFunction[String, Position, TheoryHeader](
    "fn (text, pos) => Thy_Header.read pos text"
  )
  val begin_theory = compileFunction[Path, TheoryHeader, List[Theory], Theory](
    "fn (path, header, parents) => Resources.begin_theory path header parents"
  )

  // Find out about the starter string
  private val fileContent: String = Files.readString(path_to_file.toNIO)
  if (debug) println("fileContent length: " + fileContent.length)

  private def getStarterString: String = {
    val decoyThy: Theory = Theory("Main")
    for ((transition, text) <- parse_text(decoyThy, fileContent).force.retrieveNow) {
      if (
        text.contains("theory") && text.contains(path_to_file.baseName) && text.contains("begin")
      ) {
        return text
      }
    }
    throw new Exception("Could not find theory dependencies.")
  }
  val starter_string: String = getStarterString.trim.replaceAll("\n", " ").trim
  if (debug) println("starter_string: " + starter_string)

  // Recursively find all theories in a directory (names of .thy files, without the extension).
  val available_imports: Set[String] =
    os.walk(working_dir).filter(_.ext == "thy").map(_.last).map(_.stripSuffix(".thy")).toSet
  if (debug) println("available_imports: " + available_imports)

  def sanitiseInDirectoryName(fileName: String): String = {
    fileName.replace("\"", "").split("/").last.split(".thy").head
  }
  val theoryNames: List[String] = starter_string
    .split("imports")(1)
    .split("begin")(0)
    .split(" ")
    .map(_.trim)
    .filter(_.nonEmpty)
    .toList
  var importMap = Map[String, String]()
  for (theory_name <- theoryNames) {
    val sanitisedName = sanitiseInDirectoryName(theory_name)
    println("theory_name=" + theory_name + " sanitisedName=" + sanitisedName)
    if (available_imports(sanitisedName)) {
      importMap += (theory_name.replace("\"", "") -> sanitisedName)
    } else {
      println("Warning: theory " + theory_name + " not found.")
    }
  }

  var top_level_state_map: Map[String, MLValue[ToplevelState]] = Map()
  println("wd=" + setup.workingDirectory.resolve(""))
  val theoryStarter: TheoryText = TheoryText(starter_string, setup.workingDirectory.resolve(""))
  if (debug) println("Checkpoint 9")

  def beginTheory(source: TheoryText)(implicit isabelle: Isabelle, ec: ExecutionContext): Theory = {
    val header = header_read(source.text, source.position).retrieveNow
    if (debug) println("beginTheory header: " + header)
    val registers: ListBuffer[String] = new ListBuffer[String]()
    if (debug) println("beginTheory registers: " + registers)
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
        registers += s"${sessionName}.${importMap(theory_name)}"
      } else {
        registers += theory_name
      }
    }

    if (debug) {
      println("source.path=" + source.path)
      println("header=" + header)
      println("registers=" + registers.toList)
      println("begin theory...")
    }

    val thy =
      begin_theory(source.path, header, registers.toList.map(Theory.apply)).force.retrieveNow
    if (debug) println("began theory.")
    thy
  }
  var thy1: Theory = beginTheory(theoryStarter)
  if (debug) println("Checkpoint 9_6")
  thy1.await
  if (debug) println("Checkpoint 10")

  /*
  // setting up Sledgehammer
  // val thy_for_sledgehammer: Theory = Theory("HOL.List")
  val thy_for_sledgehammer = thy1
  val Sledgehammer: String =
    thy_for_sledgehammer.importMLStructureNow("Sledgehammer")
  val Sledgehammer_Commands: String =
    thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Commands")
  val Sledgehammer_Prover: String =
    thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Prover")


  // prove_with_Sledgehammer is mostly identical to check_with_Sledgehammer except for that when the returned Boolean is true, it will
  // also return a non-empty list of Strings, each of which contains executable commands to close the top subgoal. We might need to chop part of
  // the string to get the actual tactic. For example, one of the string may look like "Try this: by blast (0.5 ms)".
  if (debug) println("Checkpoint 11")
  val normal_with_Sledgehammer =
    compileFunction[ToplevelState, Theory, List[String], List[String], (Boolean, (String, List[String]))](
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
   */

  var toplevel: ToplevelState = init_toplevel().force.retrieveNow
  if (debug) println("Checkpoint 12")

  // def reset_map(): Unit = {
  //   top_level_state_map = Map()
  // }

  // def reset_prob(): Unit = {
  //   thy1 = beginTheory(theoryStarter)
  //   toplevel = init_toplevel().force.retrieveNow
  //   reset_map()
  // }

  def getStateString(top_level_state: ToplevelState): String =
    toplevel_string_of_state(top_level_state).force.retrieveNow

  def getProofLevel(top_level_state: ToplevelState): Int =
    proof_level(top_level_state).retrieveNow

  def singleTransition(
      single_transition: Transition.T,
      top_level_state: ToplevelState,
      timeout_in_millis: Option[Int] = None
  ): ToplevelState = {
    (timeout_in_millis match {
      case Some(value) =>
        command_exception_with_timeout(value, true, single_transition, top_level_state)
      case None =>
        command_exception(true, single_transition, top_level_state)
    }).retrieveNow.force
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
    var tls_to_return: ToplevelState = clone_tls_scala(top_level_state)
    var stateString: String          = ""
    val continue                     = new Breaks
    if (debug) println("Starting to step")
    val f_st = Future.apply {
      blocking {
        Breaks.breakable {
          if (debug) println("start parsing")
          for ((transition, text) <- parse_text(thy1, isar_string).force.retrieveNow)
            continue.breakable {
              if (text.trim.isEmpty) continue.break()
              tls_to_return = singleTransition(transition, tls_to_return, Some(timeout_in_millis))
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

  // def normal_with_hammer(
  //     top_level_state: ToplevelState,
  //     added_names: List[String],
  //     deleted_names: List[String],
  //     timeout_in_millis: Int = 35000
  // ): (Boolean, List[String]) = {
  //   val f_res: Future[(Boolean, List[String])] = Future.apply {
  //     val first_result = normal_with_Sledgehammer(
  //       top_level_state,
  //       thy1,
  //       added_names,
  //       deleted_names
  //     ).force.retrieveNow
  //     (first_result._1, first_result._2._2)
  //   }
  //   Await.result(f_res, Duration(timeout_in_millis, "millis"))
  // }

  def step_to_transition_text(
      isar_string: Option[String],
      after: Boolean = true
  ): String = {
    var stateString: String = ""
    val continue            = new Breaks
    if (debug) println("step_to_transition_text first iteration")
    Breaks.breakable {
      for ((transition, text) <- parse_text(thy1, fileContent).force.retrieveNow) {
        continue.breakable {
          print("transition=" + get_transition_name(transition).force.retrieveNow)
          print(" is_init=" + is_transition_init(transition).force.retrieveNow)
          if (is_transition_malformed(transition).force.retrieveNow)
            throw new Exception("Malformed transition")
          println(" text='''" + text.trim + "'''")
          if (text.trim.isEmpty) continue.break()
          val trimmed_text =
            text.trim.replaceAll("\n", " ").replaceAll(" +", " ")
          isar_string match {
            case Some(isar_string) =>
              if (trimmed_text == isar_string) {
                if (after) {
                  if (debug) println("step_to_transition_text last transition")
                  stateString = singleTransition(transition)
                }
                return stateString
              }
            case None =>
              if (is_end_theory(toplevel).force.retrieveNow)
                throw new Exception("End of theory before end of file.")
          }
          stateString = singleTransition(transition)
          println(
            "stateString='''\n"
              + stateString.trim.linesWithSeparators.map("\t\t" + _).mkString
              + "\n'''"
          )
        }
      }
    }
    if (!is_end_theory(toplevel).force.retrieveNow)
      throw new Exception("End of file before end of theory.")
    isar_string match {
      case Some(isar_string) =>
        throw new Exception("Did not find the text. Current state: " + stateString)
      case None =>
        stateString
    }
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
