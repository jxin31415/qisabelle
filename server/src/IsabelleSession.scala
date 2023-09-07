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

/** @param isabelleDir
  *   Path to the Isabelle distribution directory (should contain bin/isabelle).
  * @param sessionName
  *   Name of Isabelle session (as defined by ROOT files) whose heap (a session state cache) we will
  *   load. If you just use 'HOL', loading a theory will take a long time, building all its imports.
  *   Loading a heap after the process started seems to be impossible, see
  *   `Isabelle/src/Pure/ML/ml_process.scala`.
  * @param sessionRoots
  *   Additional directories in which Isabelle can find sessions (using ROOT and ROOTS files).
  *   (These are passed as '-d' options to the 'isabelle build' process.)
  * @param workingDir
  *   Working directory for the Isabelle process. This doesn't influence a lot, mostly how relative
  *   paths are resolved for imports that are not found in the heap. It could be changed later if
  *   you need to.
  * @param defaultPerTransitionTimeout
  *   Default for `perTransitionTimeout` in `parseAndExecute()`.
  * @param defaultParseAndExecuteTimeout
  *   Default for `totalTimeout` in `parseAndExecute()`.
  * @param debug
  */
class IsabelleSession(
    val isabelleDir: os.Path,
    val sessionName: String,
    val sessionRoots: Seq[os.Path],
    val workingDir: os.Path,
    val defaultPerTransitionTimeout: Duration = Duration.Inf,
    val defaultParseAndExecuteTimeout: Duration = Duration.Inf,
    val debug: Boolean = true
) {
  if (debug) {
    println("sessionName: " + sessionName)
    println("sessionRoots: " + sessionRoots)
    println("isabelleDir: " + isabelleDir)
    println("workingDir: " + workingDir)
  }

  val setup: Isabelle.Setup = Isabelle.Setup(
    isabelleHome = isabelleDir.toNIO,
    sessionRoots = sessionRoots.map(_.toNIO),
    userDir = None, // Use default '.isabelle' dir to store heaps in.
    logic = sessionName,
    workingDirectory = workingDir.toNIO,
    build = false // Currently has no effect, the heap is always built.
  )
  implicit val isabelle: Isabelle   = new Isabelle(setup)
  implicit val ec: ExecutionContext = setup.executionContext

  if (debug) println("IsabelleSession: initializing parser...")
  val time = System.currentTimeMillis()
  Transition.parseOuterSyntax(Theory("Main"), "")
  val elapsed = (System.currentTimeMillis() - time) / 1000.0
  if (debug) println(s"IsabelleSession: initialized parser in ${elapsed} s.")

  def close(): Unit = {
    isabelle.destroy()
  }

  /** Parse and execute Isar code.
    *
    * @param isarCode
    *   Isar "outer syntax". Can include many transitions (lemma statements, proof steps).
    * @param initState
    *   State to apply transitions to.
    * @param perTransitionTimeout
    *   Timeout for each transition; for some reason it gets rounded up to full seconds by Isabelle,
    *   see `Transition.execute`.
    * @param totalTimeout
    *   Hard timeout for the total of all the parsing and transition execution.
    * @param debug
    *   Whether to print debug output, including each transition and each resulting state.
    */
  @throws(classOf[IsabelleMLException])
  @throws(classOf[TimeoutException])
  def parseAndExecute(
      isarCode: String,
      initState: ToplevelState,
      perTransitionTimeout: Duration = defaultPerTransitionTimeout,
      totalTimeout: Duration = defaultParseAndExecuteTimeout,
      debug: Boolean = true
  ): ToplevelState = {
    if (debug) println("parseAndExecute: begin")
    var state: ToplevelState = initState
    val theory               = initState.theory
    val f_st = Future.apply {
      blocking {
        if (debug) println("parseAndExecute: parsing")
        val transitions = Transition.parseOuterSyntax(theory, isarCode)
        if (debug) println("parseAndExecute: execute")
        for ((transition, text) <- transitions) {
          if (debug) println(ParsedTheory.describeTransition(transition, text))
          if (!text.trim.isEmpty) {
            state = transition.execute(state, perTransitionTimeout)
            if (debug) println(ParsedTheory.describeState(state))
          }
        }
      }
    }
    if (debug) println("parseAndExecute: await")
    Await.result(f_st, totalTimeout)
    return state
  }
}

object IsabelleSession {

  /** Guess name of session (as defined in an Isabelle ROOT file) from theory file path.
    *
    * A better way would be to parse ROOT files in all ancestors (or all sessionRoots) and find the
    * session which contains our theory.
    *
    * @param theoryPath
    *   Path to .thy file.
    */
  def guessSessionName(theoryPath: os.Path): String = {
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

  /** Guess session roots (directories in which to search for sessions) from theory path.
    *
    * @param theoryPath
    *   Path to .thy file.
    */
  def guessSessionRoots(theoryPath: os.Path): Seq[os.Path] = {
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

  protected def getPathPrefix(path: os.Path, n_segments: Int): os.Path = {
    os.Path("/" + path.segments.take(n_segments).mkString("/"))
  }
}
