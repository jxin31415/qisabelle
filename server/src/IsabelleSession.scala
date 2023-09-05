package server

import util.control.Breaks
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException, blocking}
import scala.concurrent.duration.Duration
import scalaz.std.java.time
import scalaz.std.string
import scala.util.{Success, Failure}
import scala.util.matching.Regex
import sys.process._
import _root_.java.nio.file.{Files, Path}
import _root_.java.io.File

import de.unruh.isabelle.control.{Isabelle, IsabelleMLException}
import de.unruh.isabelle.mlvalue.{AdHocConverter, MLValue, MLValueWrapper}
import de.unruh.isabelle.mlvalue.MLValue.{compileFunction, compileFunction0, compileValue}
import de.unruh.isabelle.pure.{Context, Position, Theory, TheoryHeader, ToplevelState, Transition}
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

import server.Sledgehammer

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

  val parsedTheory = new ParsedTheory(theoryPath, sessionName, debug)

  if (debug) println("HammerCompilation: start")
  Sledgehammer.Ops.runSledgehammer.force.retrieveNow
  if (debug) println("HammerCompilation: end")

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
          if (verbose) println(ParsedTheory.describeTransition(transition, text))
          if (!text.trim.isEmpty) {
            state = transition.execute(state, timeout)
            if (verbose) println(ParsedTheory.describeState(state))
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
