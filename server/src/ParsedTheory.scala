package server

import scala.language.postfixOps
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException, blocking}
import scala.util.matching.Regex
import _root_.java.nio.file.{Files, Path}

import de.unruh.isabelle.control.{Isabelle, IsabelleMLException, OperationCollection}
import de.unruh.isabelle.mlvalue.{MLValue, MLValueWrapper}
import de.unruh.isabelle.pure.{Theory, TheoryHeader, ToplevelState, Transition}
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

import ParsedTheory.Ops

/** An Isabelle Theory together with its parsed list of transitions.
  *
  * @param path
  *   Path to .thy file.
  * @param sessionName
  *   Name of currently loaded session (the name as defined in an Isabelle ROOT file). This is used
  *   to look for theory imports specified with a path; we assert that all imports are already
  *   loaded in the session heap.
  * @param debug
  */
class ParsedTheory(
    val path: os.Path,
    val sessionName: String,
    val debug: Boolean = true
)(implicit
    isabelle: Isabelle
) {
  val fileContent: String = Files.readString(path.toNIO)
  if (debug)
    println("ParsedTheory fileContent.length=" + fileContent.length + " " + path.toString())
  val masterDir: os.Path = path / os.up

  val theoryHeader: TheoryHeader = TheoryHeader.read(fileContent)
  if (debug)
    println(
      "ParsedTheory header=" + theoryHeader.name + " imports=" + theoryHeader.imports.mkString(",")
    )
  if (theoryHeader.name != path.baseName)
    println(s"Warning: theory name (${theoryHeader.name}) != filename (${path.baseName}).")

  // Lookup imports, assert they're all already loaded in the session heap.
  val imports: List[Theory] = ParsedTheory
    .loadImports(
      theoryHeader.imports,
      sessionName,
      masterDir,
      onlyFromSessionHeap = true,
      debug = debug
    )
    .toList

  if (debug) println("ParsedTheory begin... ")
  val theory = Ops.beginTheory(masterDir.toNIO.resolve(""), theoryHeader, imports).retrieveNow
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

  def normalizeWhitespace(s: String): String = {
    s.trim.replaceAll("\n", " ").replaceAll(" +", " ")
  }

  /** Execute all transitions, print the text of first/last few for debugging.
    *
    * @param initState
    *   State to start from: the default empty state is usually good.
    * @param nDebug
    *   Print this many first & last non-empty transitions & resulting states.
    * @param stopBeforeEnd
    *   If true, stop execution before the "theory .. end" transition, allowing to continue a
    *   theory.
    * @return
    *   State after the last transition: in Theory mode if stopBeforeEnd is true, in Toplevel mode
    *   otherwise.
    */
  @throws(classOf[IsabelleMLException])
  @throws(classOf[TimeoutException])
  def executeAll(
      initState: ToplevelState = ToplevelState(),
      nDebug: Integer = 0,
      stopBeforeEnd: Boolean = false
  ): ToplevelState = {
    execute(transitions, initState, nDebug = nDebug, stopBeforeEnd = stopBeforeEnd)
  }

  /** Execute all transitions until a given one, print the text of first/last few for debugging.
    *
    * @param isarString
    *   Transition string to execute until (whitespace is ignored when comparing).
    * @param inclusive
    *   Whether to execute the specified transition as well.
    * @param initState
    *   State to start from: the default empty state is usually good.
    * @param nDebug
    *   Print this many first & last non-empty transitions & resulting states.
    */
  @throws(classOf[IsabelleMLException])
  @throws(classOf[TimeoutException])
  def executeUntil(
      isarString: String,
      inclusive: Boolean,
      initState: ToplevelState = ToplevelState(),
      nDebug: Integer = 0
  ): ToplevelState = {
    execute(takeUntil(isarString, inclusive = inclusive), initState, nDebug)
  }

  /** Execute a list of transitions, print the text of first/last few for debugging.
    *
    * @param transitions
    *   Transitions to execute, paired with their text (Isar outer syntax).
    * @param initState
    *   State to start from: the default empty state is usually good.
    * @param nDebug
    *   Print this many first & last non-empty transitions & resulting states.
    * @param stopBeforeEnd
    *   If true, stop execution before the "theory .. end" transition, allowing to continue a theory
    *   after executing all it's transitions.*
    */
  @throws(classOf[IsabelleMLException])
  @throws(classOf[TimeoutException])
  def execute(
      transitions: List[(Transition, String)],
      initState: ToplevelState = ToplevelState(),
      nDebug: Integer = 0,
      stopBeforeEnd: Boolean = false
  ): ToplevelState = {
    var state: ToplevelState = initState
    // Skip empty transitions, to speed-up execution and ease debugging.
    val nonEmptyTransitions = transitions.filter(!_._2.trim.isEmpty)

    for (((transition, text), i) <- nonEmptyTransitions.zipWithIndex) {
      if (debug) {
        if (i < nDebug || i >= nonEmptyTransitions.length - nDebug)
          println(ParsedTheory.describeTransition(transition, text))
        else
          print(".") // Progress indicator.
        System.out.flush()
      }

      val newState = transition.execute(state)
      if (stopBeforeEnd && newState.isEndTheory)
        return state
      state = newState

      if (debug && (i < nDebug || i >= nonEmptyTransitions.length - nDebug))
        println(ParsedTheory.describeState(state))
    }

    state
  }
}

object ParsedTheory extends OperationCollection {
  def indent(s: String, indentation: String = "\t"): String = {
    s.linesWithSeparators.map(indentation + _).mkString
  }
  def describeTransition(tr: Transition, text: String)(implicit isabelle: Isabelle): String = {
    var s = s"Transition[name=${tr.name}, is_init=${tr.isInit}"
    if (text.trim.isEmpty)
      s + "]"
    else
      s + s", text='''\n${indent(text.trim)}\n''']"
  }
  def describeState(state: ToplevelState)(implicit isabelle: Isabelle): String = {
    var s = s"State[mode=${state.mode}, localTheory='${state.localTheoryDescription}'"
    if (state.proofStateDescription.trim.isEmpty)
      s + "]"
    else
      s + s", proofState='''\n${indent(state.proofStateDescription.trim)}\n''']"
  }
  def getMode(state: ToplevelState)(implicit isabelle: Isabelle): String = {
    s"${state.mode}"
  }
  def getTheory(state: ToplevelState)(implicit isabelle: Isabelle): String = {
    s"${state.localTheoryDescription}"
  }
  def getProofStateDescription(state: ToplevelState)(implicit isabelle: Isabelle): String = {
    s"${state.proofStateDescription.trim}"
  }

  /** Turns an import string from a theory header into a theory name that can be loaded.
    *
    * For theories already loaded in the heap (which is all we want here), this is:
    *   - for path imports: session_name + "." + (filename from the path).
    *   - for literal imports (non-paths): the import string, unchanged.
    *
    * For new theories, you may want to use Resources.default_qualifier == "Draft" as the session
    * name.
    *
    * The masterDir is only used for relative paths, only if they are not already in the heap.
    */
  def getImportName(importString: String, sessionName: String, masterDir: Path)(implicit
      isabelle: Isabelle
  ): String = {
    Ops.getImportName(importString, sessionName, masterDir).force.retrieveNow
  }

  /** Check if a theory is already loaded in the heap. */
  def isTheoryLoaded(theoryName: String)(implicit isabelle: Isabelle): Boolean = {
    Ops.isTheoryLoaded(theoryName).force.retrieveNow
  }

  /** Load imports of a theory.
    *
    * @param imports
    *   Names or paths as used in Isar 'theory Foo imports ... begin'.
    * @param sessionName
    *   Name of currently loaded Isabelle session. Used to load imports specified with a path.
    * @param masterDir
    *   Used to resolve relative paths of theories not available in session heap.
    * @param onlyFromSessionHeap
    *   If true, throw an exception if any import is not already loaded in the session heap.
    * @param debug
    */
  def loadImports(
      imports: Seq[String],
      sessionName: String = "Draft",
      masterDir: os.Path = os.pwd,
      onlyFromSessionHeap: Boolean = true,
      debug: Boolean = true
  )(implicit isabelle: Isabelle): Seq[Theory] = {
    val importNames: Seq[String] =
      imports.map(ParsedTheory.getImportName(_, sessionName, masterDir.toNIO))
    if (debug) println("ParsedTheory importNames=" + importNames)

    if (onlyFromSessionHeap)
      for (name <- importNames)
        if (!ParsedTheory.isTheoryLoaded(name))
          throw new Exception("Theory not loaded in session heap: " + name)

    importNames.map(Theory(_))
  }

  protected final class Ops(implicit isabelle: Isabelle) {
    import MLValue.compileFunction

    lazy val getImportName = compileFunction[String, String, Path, String](
      """fn (import_string, session_name, master_dir) =>
        |  let
        |    val {theory_name, ...} = Resources.import_name session_name master_dir import_string;
        |  in theory_name end
        |""".stripMargin
    )

    lazy val isTheoryLoaded = compileFunction[String, Boolean]("Resources.loaded_theory")

    lazy val beginTheory = MLValue.compileFunction[Path, TheoryHeader, List[Theory], Theory](
      "fn (master_dir, header, parents) => Resources.begin_theory master_dir header parents"
    )
  }
  override protected def newOps(implicit isabelle: Isabelle): Ops = new Ops()
}
