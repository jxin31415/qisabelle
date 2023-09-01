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
import de.unruh.isabelle.mlvalue.{AdHocConverter, MLValue, MLValueWrapper}
import de.unruh.isabelle.mlvalue.MLValue.{compileFunction, compileFunction0, compileValue}
import de.unruh.isabelle.pure.{Context, Position, Theory, TheoryHeader, ToplevelState, Transition}

// Implicits
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.control.IsabelleMLException
import scalaz.std.java.time
import scalaz.std.string

class IsabelleSession(
    val isabelleDir: os.Path, // Path to the Isabelle distribution directory (should contain bin/isabelle).
    val theoryPath: os.Path, // Path to .thy file to load.
    val workingDir: os.Path,
    val useSledgehammer: Boolean = false,
    var debug: Boolean = false
) {
  // Name of session (defined in an Isabelle ROOT file) whose heap we will load.
  // Here we guess it from the folder name of the current project.
  // A better way would be to parse ROOT files in all ancestors and find the session
  // which contains our theory.
  val sessionName: String = {
    if (theoryPath.toString().contains("afp")) {
      theoryPath.getSegment(theoryPath.segments.indexOf("thys") + 1)
    } else if (theoryPath.toString().contains("Isabelle") && theoryPath.segments.contains("src")) {
      (theoryPath / os.up).segments
        .drop(theoryPath.segments.indexOf("src") + 1)
        .mkString("-")
    } else if (theoryPath.toString().contains("miniF2F")) {
      "HOL"
    } else {
      throw new Exception("Unsupported file path:" + theoryPath.toString())
    }
  }

  def getPathPrefix(path: os.Path, n_segments: Int): os.Path = {
    os.Path("/" + path.segments.take(n_segments).mkString("/"))
  }
  // Directories in which Isabelle can find sessions (using ROOT and ROOTS files).
  val sessionRoots: Seq[os.Path] = {
    if (theoryPath.toString().contains("afp")) {
      // Use theoryPath until "thys", inclusive (which means all of AFP).
      Seq(getPathPrefix(theoryPath, theoryPath.segments.indexOf("thys") + 1))
    } else if (theoryPath.toString().contains("Isabelle") && theoryPath.segments.contains("src")) {
      // Use theoryPath until "src", inclusive, and one more segment (the logic, like "HOL").
      Seq(getPathPrefix(theoryPath, theoryPath.segments.indexOf("src") + 2))
    } else if (theoryPath.toString().contains("miniF2F")) {
      // No non-system imports needed.
      Seq()
    } else {
      throw new Exception("Unsupported file path:" + theoryPath.toString())
    }
  }
  if (debug) {
    println("sessionName: " + sessionName)
    println("sessionRoots: " + sessionRoots)
    println("isabelleDir: " + isabelleDir)
    println("workingDir: " + workingDir)
  }

  // Start the actual Isabelle process.
  val setup: Isabelle.Setup = Isabelle.Setup(
    isabelleHome = isabelleDir.toNIO,
    // Additional session directories in which Isabelle will search for sessions;
    // these are passed as '-d' options to the 'isabelle build' process:
    sessionRoots = sessionRoots.map(_.toNIO),
    // Use default '.isabelle' dir to store heaps in:
    userDir = None, // Some(Path.of("/tmp/qisabelle/.isabelle")),
    // Session whose heap image (a state cache) we load in Isabelle.
    // If I just use 'HOL', Resources.begin_theory takes a long time.
    // Loading a heap after the process started seems to be impossible,
    // see Isabelle/src/Pure/ML/ml_process.scala.
    logic = sessionName,
    // The working dir doesn't seem to matter anymore (at least not for theory imports).
    workingDirectory = workingDir.toNIO,
    build = false // Currently has no effect, the heap is always built.
  )
  implicit val isabelle: Isabelle   = new Isabelle(setup)
  implicit val ec: ExecutionContext = ExecutionContext.global
  if (debug) println("Isabelle constructed.")

  if (debug) println("Initializing parser...")
  val time = System.currentTimeMillis()
  Transition.parseOuterSyntax(Theory("Main"), "")
  if (debug) println(s"Initialized parser in ${(System.currentTimeMillis() - time) / 1000} s.")

  def close(): String = {
    isabelle.destroy()
    return "Closed"
  }

  /** Turns an import string from a theory header into a theory name that can be loaded.
    *
    * For theories already loaded in the heap (which is all we want here), this is:
    *   - for path imports: session_name + "." + (filename from the path).
    *   - for literal imports (non-paths): the import string, unchanged. For new theories, you may
    *     want to use Resources.default_qualifier == "Draft" as the session name.
    *
    * The master_dir is only used for relative paths, only if they are not already in the heap.
    */
  private val _getImportName = compileFunction[String, String, Path, String]("""
    |fn (import_string, session_name, master_dir) =>
    |  let
    |    val {theory_name, ...} = Resources.import_name session_name master_dir import_string;
    |  in theory_name end
    |""".stripMargin)
  def getImportName(importString: String, sessionName: String, masterDir: Path): String = {
    _getImportName(importString, sessionName, masterDir).force.retrieveNow
  }

  /** Check if a theory is already loaded in the heap. */
  private val _isTheoryLoaded = compileFunction[String, Boolean]("Resources.loaded_theory")
  def isTheoryLoaded(theoryName: String): Boolean = {
    _isTheoryLoaded(theoryName).force.retrieveNow
  }

  private val _beginTheory = MLValue.compileFunction[Path, TheoryHeader, List[Theory], Theory](
    "fn (master_dir, header, parents) => Resources.begin_theory master_dir header parents"
  )

  def normalizeWhitespace(s: String): String = {
    s.trim.replaceAll("\n", " ").replaceAll(" +", " ")
  }

  class ParsedTheory(val path: os.Path, val masterDir: os.Path) {
    val fileContent: String = Files.readString(theoryPath.toNIO)
    if (debug) println("ParsedTheory fileContent.length=" + fileContent.length)

    val theoryHeader: TheoryHeader = TheoryHeader.read(fileContent)
    if (debug) println("ParsedTheory header=" + theoryHeader)
    if (theoryHeader.name != theoryPath.baseName)
      println(s"Warning: theory name (${theoryHeader.name}) != filename (${theoryPath.baseName}).")

    // Lookup imports, assert they're already loaded in the heap.
    val importNames: List[String] =
      theoryHeader.imports.map(getImportName(_, sessionName, masterDir.toNIO))
    if (debug) println("ParsedTheory importNames=" + importNames)
    importNames.find(!isTheoryLoaded(_)) match {
      case Some(name) => throw new Exception("Theory not loaded in session heap: " + name)
      case None       => ()
    }
    val imports: List[Theory] = importNames.map(Theory(_))

    if (debug) println("ParsedTheory begin...")
    val theory = _beginTheory(masterDir.toNIO.resolve(""), theoryHeader, imports).retrieveNow
    // Alternatively, we could just do `Theory.begin_theory (name, Position.none) imports`,
    // which is what `Theory.mergeTheories(theoryHeader.name, false, imports)` would do,
    // but this is probably closer to what Isabelle/jEdit does (it loads keywords etc.).
    if (debug) println("ParsedTheory await..")
    theory.await
    if (debug) println("ParsedTheory parsing...")
    val transitions = Transition.parseOuterSyntax(theory, fileContent)
    if (debug) println("ParsedTheory done.")

    /** Transitions (with their text) until the "theory .. begin" transition, inclusive. */
    def initTransitions(): List[(Transition, String)] = {
      transitions.take(transitions.indexWhere(_._1.isInit) + 1).toList
    }

    /** Transitions (with their text) until a given one. */
    def takeUntil(isarString: String, inclusive: Boolean): List[(Transition, String)] = {
      val s     = normalizeWhitespace(isarString)
      val index = transitions.indexWhere(t => normalizeWhitespace(t._2) == s)
      if (index == -1)
        throw new Exception("Transition not found: " + isarString)
      if (inclusive)
        transitions.take(index + 1).toList
      else
        transitions.take(index).toList
    }
  }
  val parsedTheory = new ParsedTheory(theoryPath, workingDir)

  def indent(s: String, indentation: String = "\t"): String = {
    s.linesWithSeparators.map(indentation + _).mkString
  }
  def describeTransition(transition: Transition, text: String): String = {
    var s = s"Transition[name=${transition.name}, is_init=${transition.isInit}"
    if (text.trim.isEmpty)
      s + "]"
    else
      s + s", text='''\n${indent(text.trim)}\n''']"
  }
  def describeState(state: ToplevelState): String = {
    var s = s"State[mode=${state.mode}, localTheory='${state.localTheoryDescription}'"
    if (state.proofStateDescription.trim.isEmpty)
      s + "]"
    else
      s + s", proofState='''\n${indent(state.proofStateDescription.trim)}\n''']"
  }

  /** Execute a list of transitions, print the text of a first few for debugging. */
  @throws(classOf[IsabelleMLException])
  @throws(classOf[TimeoutException])
  def execute(
      transitions: List[(Transition, String)],
      initState: ToplevelState = ToplevelState(),
      nDebug: Integer = 0 // How many first and last non-empty transitions/states to print.
  ): ToplevelState = {
    var state: ToplevelState = initState // clone_tls_scala(initState)
    // Skip empty transitions, to speed-up execution and ease debugging.
    // TODO is skipping ignored transitions just as fast? Could be slower due to interaction with Isabelle, could be faster.
    val nonEmptyTransitions = transitions.filter(!_._2.trim.isEmpty)

    for (((transition, text), i) <- nonEmptyTransitions.zipWithIndex) {
      if (debug) {
        if (i < nDebug || i >= nonEmptyTransitions.length - nDebug)
          println(describeTransition(transition, text))
        else
          print("-") // TODO println if vscode console?
        System.out.flush()
      }

      state = transition.execute(state)

      if (debug && (i < nDebug || i >= nonEmptyTransitions.length - nDebug))
        println(describeState(state))
    }

    state
  }

  // ------------------------------------------------- setting up Sledgehammer
  if (debug) println("Hammer: start")
  val theoryForHammer                = Theory("HOL.List") // parsedTheory.theory
  val mlSledgehammer: String         = theoryForHammer.importMLStructureNow("Sledgehammer")
  val mlSledgehammerCommands: String = theoryForHammer.importMLStructureNow("Sledgehammer_Commands")
  val mlSledgehammerProver: String   = theoryForHammer.importMLStructureNow("Sledgehammer_Prover")

  // prove_with_Sledgehammer is mostly identical to check_with_Sledgehammer except for that when the returned Boolean is true, it will
  // also return a non-empty list of Strings, each of which contains executable commands to close the top subgoal. We might need to chop part of
  // the string to get the actual tactic. For example, one of the string may look like "Try this: by blast (0.5 ms)".
  if (debug) println("Hammer: main compile")
  val normalWithSledgehammer =
    compileFunction[
      ToplevelState,
      Theory,
      List[String],
      List[String],
      (Boolean, (String, List[String]))
    ](
      s"""fn (state, thy, adds, dels) =>
         |    let
         |       fun get_refs_and_token_lists (name) = (Facts.named name, []);
         |       val adds_refs_and_token_lists = map get_refs_and_token_lists adds;
         |       val dels_refs_and_token_lists = map get_refs_and_token_lists dels;
         |       val override = {add=adds_refs_and_token_lists,del=dels_refs_and_token_lists,only=false};
         |       fun go_run (state, thy) =
         |          let
         |             val p_state = Toplevel.proof_of state;
         |             val ctxt = Proof.context_of p_state;
         |             val params = ${mlSledgehammerCommands}.default_params thy
         |                [("provers", "cvc5 vampire verit e spass z3 zipperposition"),("timeout","30"),("verbose","true")];
         |             val results = ${mlSledgehammer}.run_sledgehammer params ${mlSledgehammerProver}.Normal NONE 1 override p_state;
         |             val (result, (outcome, step)) = results;
         |           in
         |             (result, (${mlSledgehammer}.short_string_of_sledgehammer_outcome outcome, [YXML.content_of step]))
         |           end;
         |    in
         |      Timeout.apply (Time.fromSeconds 350) go_run (state, thy) end
         |""".stripMargin
    )
  if (debug) println("Hammer: end")
  // TODO timeout, preplay_timeout,  dont_preplay, try0, smt_proofs, isar_proofs, max_proofs, verbose, induction_rules, learn, fact_filter, minimize

  def normalWithHammer(
      state: ToplevelState,
      addedNames: List[String] = List(),
      deletedNames: List[String] = List(),
      timeout: Duration = Duration.Inf // Duration(35000, "millis")
  ): (Boolean, String, List[String]) = {
    val f_res: Future[(Boolean, String, List[String])] = Future.apply {
      val r = normalWithSledgehammer(
        state,
        parsedTheory.theory,
        addedNames,
        deletedNames
      ).force.retrieveNow
      (r._1, r._2._1, r._2._2)
    }
    Await.result(f_res, timeout)
  }

  @throws(classOf[IsabelleMLException])
  @throws(classOf[TimeoutException])
  def step(
      isarCode: String,
      initState: ToplevelState,
      timeout: Duration = Duration.Inf,
      verbose: Boolean = true
  ): ToplevelState = {
    if (debug) println("Step: begin")
    var state: ToplevelState = initState // clone_tls_scala(state)
    val f_st = Future.apply {
      blocking {
        if (debug) println("Step: parsing")
        val transitions = Transition.parseOuterSyntax(parsedTheory.theory, isarCode)
        if (debug) println("Step: execute")
        for ((transition, text) <- transitions) {
          if (verbose) println(describeTransition(transition, text))
          if (!text.trim.isEmpty) {
            state = transition.execute(state, timeout)
            if (verbose) println(describeState(state))
          }
        }
      }
    }
    if (debug) println("Step: await")

    // Await for infinite amount of time
    Await.result(f_st, Duration.Inf)
    state
  }

  // Manage top level states with the internal map
  var top_level_state_map: Map[String, MLValue[ToplevelState]] = Map()
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

  // def reset_map(): Unit = {
  //   top_level_state_map = Map()
  // }

  // def reset_prob(): Unit = {
  //   thy1 = beginTheory(theoryStarter)
  //   toplevel = ToplevelState()
  //   reset_map()
  // }

}