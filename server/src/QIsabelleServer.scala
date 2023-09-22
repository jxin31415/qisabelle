package server

import scala.concurrent.duration._
import scala.util.matching.Regex

import de.unruh.isabelle.pure.{Theory, ToplevelState}
import de.unruh.isabelle.control.IsabelleMLException
import de.unruh.isabelle.mlvalue.MLValue
import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.mlvalue.Implicits._
import org.checkerframework.checker.units.qual

case class QIsabelleRoutes()(implicit cc: castor.Context, log: cask.Logger) extends cask.Routes {
  // These are the timeouts originally used in PISA.
  val perTransitionTimeout: Duration   = 10.seconds
  val parseAndExecuteTimeout: Duration = Duration.Inf
  val softHammerTimeout: Duration      = 30.seconds
  val midHammerTimeout: Duration       = 35.seconds
  val hardHammerTimeout: Duration      = 40.seconds

  var session: IsabelleSession   = null
  var sledgehammer: Sledgehammer = null

  /** Remembering states by name for the API. */
  var stateMap: Map[String, ToplevelState] = null

  /** A dummy endpoint to just check if the server is running. */
  @cask.get("/")
  def hello() = {
    "Hello from QIsabelle"
  }

  /** Start an Isabelle process and load a given theory up until a given transition.
    *
    * @param theoryPath
    *   Path to .thy file from which we guess the session name and session roots to load.
    * @param workingDir
    *   The working directory of the Isabelle process. Doesn't influence anything important anymore;
    *   it would be used for resolving relative theory paths for theories that are not found
    *   pre-built in the session heap, but we throw an exception if that happens anyway. You can use
    *   the directory containing the theory file, for example.
    *
    * @return
    *   {"success": "success"} or {"error": str, "traceback": str}
    */
  @cask.postJson("/openIsabelleSession")
  def openIsabelleSession(workingDir: String, theoryPath: String): ujson.Obj = {
    println("openIsabelleSession: start")
    try {
      val sessionName  = IsabelleSession.guessSessionName(os.Path(theoryPath))
      val sessionRoots = IsabelleSession.guessSessionRoots(os.Path(theoryPath))

      session = new IsabelleSession(
        isabelleDir = os.Path("/home/isabelle/Isabelle/"),
        sessionName = sessionName,
        sessionRoots = sessionRoots,
        workingDir = os.Path(workingDir),
        defaultPerTransitionTimeout = perTransitionTimeout,
        defaultParseAndExecuteTimeout = parseAndExecuteTimeout,
        debug = true
      )
      implicit val isabelle = session.isabelle
      sledgehammer = new Sledgehammer(
        softTimeout = softHammerTimeout,
        midTimeout = midHammerTimeout,
        hardTimeout = hardHammerTimeout,
        debug = true
      )
      stateMap = Map()
      println("openIsabelleSession: end")
      return ujson.Obj("success" -> "success")
    } catch {
      case e: Throwable => { closeIsabelleSession(); exceptionJson(e) }
    }
  }

  /** Stop the Isabelle process and clear the state map and theory from memory.
    *
    * @return
    *   {"success": "Closed"} or {"error": "Already closed", "traceback": ""}
    */
  @cask.postJson("/closeIsabelleSession")
  def closeIsabelleSession(): ujson.Obj = {
    stateMap = null
    sledgehammer = null
    if (session == null)
      return ujson.Obj("error" -> "Already closed", "traceback" -> "")
    session.close()
    session = null
    return ujson.Obj("success" -> "Closed")
  }

  /** Load a given theory file until a specified transition.
    *
    * @param theoryPath:
    *   path to .thy file; it is actually loaded and fully parsed (unlike imports, which are loaded
    *   from the heap).
    * @param until:
    *   transition to stop at (Isar outer syntax; whitespace is ignored when comparing). Empty means
    *   execute all the theory until the end (`inclusive` then determines whether the theory is
    *   ended, resulting in a state in Toplevel mode instead of Theory mode).
    * @param inclusive:
    *   whether to include the transition specified by `until` in the result.
    * @return
    *   Same as `execute()`.
    */
  @cask.postJson("/loadTheory")
  def loadTheory(
      theoryPath: String,
      until: String,
      inclusive: Boolean,
      newStateName: String
  ): ujson.Obj = {
    try {
      implicit val isabelle = session.isabelle
      val parsedTheory = new ParsedTheory(os.Path(theoryPath), session.sessionName, debug = true)
      val newState = {
        if (until.nonEmpty)
          parsedTheory.executeUntil(until, inclusive = inclusive, nDebug = 3)
        else
          parsedTheory.executeAll(stopBeforeEnd = !inclusive, nDebug = 3)
      }
      stateMap += (newStateName -> newState)
      return ujson.Obj(
        "proofGoals" -> newState.proofStateDescription,
        "proofDone"  -> (newState.proofLevel == 0)
      )
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  @cask.postJson("/describeState")
  def describeState(stateName: String): ujson.Obj = {
    implicit val isabelle    = session.isabelle
    val state: ToplevelState = stateMap(stateName)
    return ujson.Obj("description" -> ParsedTheory.describeState(state))
  }

  /** Parse an execute Isar code on a given state, save the resulting state under a new name.
    *
    * @param stateName
    *   Name of state to execute on.
    * @param isarCode
    *   Isar code to execute (can be multiple transitions, like lemma statements or proofs).
    * @param newStateName
    *   Name of new state to save the result under.
    * @return
    *   - On successful execuction, a JSON object with keys:
    *     - proofGoals: like "proof (prove) goal (1 subgoal): ..." (empty if not in proof mode).
    *     - proofDone: true if there are no more subgoals (proof level is 0, the resulting toplevel
    *       state is not in proof nor skipped-proof mode).
    *   - On error: {"error": str, "traceback": str}
    */
  @cask.postJson("/execute")
  def execute(stateName: String, isarCode: String, newStateName: String): ujson.Obj = {
    implicit val isabelle    = session.isabelle
    val state: ToplevelState = stateMap(stateName)

    try {
      val newState: ToplevelState =
        session.parseAndExecute(isarCode, state, debug = true)
      stateMap += (newStateName -> newState)

      return ujson.Obj(
        "proofGoals" -> newState.proofStateDescription,
        "proofDone"  -> (newState.proofLevel == 0)
      )
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  /** Erase a given state from the state map. */
  @cask.postJson("/forgetState")
  def forgetState(stateName: String): ujson.Obj = {
    stateMap -= stateName
    return ujson.Obj("success" -> "success")
  }

  /** Clear the state map. */
  @cask.postJson("/forgetAllStates")
  def forgetAllStates(stateName: String): ujson.Obj = {
    stateMap = Map()
    return ujson.Obj("success" -> "success")
  }

  /** Run Sledgehammer on a given state.
    *
    * @param stateName
    *   Name of state to run Sledgehammer on (on first subgoal, state must be in proof mode).
    * @param addedFacts
    *   List of fact names to suggest to Sledgehammer.
    * @param deletedFacts
    *   List of fact names to forbid to Sledgehammer.
    * @return
    *   - On success: {"proof": proof}; proof is e.g. "using .. by ..."; the proof is not guaranteed
    *     to be correct.
    *   - On error: {"error": str, "traceback": str}; common errors start with "Sledgehammer
    *     timeout" or "Sledgehammer no proof found".
    */
  @cask.postJson("/hammer")
  def hammer(stateName: String, addedFacts: List[String], deletedFacts: List[String]): ujson.Obj = {
    implicit val isabelle    = session.isabelle
    val state: ToplevelState = stateMap(stateName)

    try {
      return ujson.Obj("proof" -> sledgehammer.findProofOrThrow(state, addedFacts, deletedFacts))
    } catch {
      case e: Throwable => exceptionJson(e)
    }
  }

  def exceptionJson(e: Throwable): ujson.Obj = {
    println(e.toString())
    println("\t\t" + e.getStackTrace().mkString("\n\t\t"))
    ujson.Obj("error" -> e.toString(), "traceback" -> e.getStackTrace().mkString("\n"))
  }

  initialize()
}

object QISabelleServer extends cask.Main {
  val allRoutes                   = Seq(QIsabelleRoutes())
  override def debugMode: Boolean = true
  override def host: String       = sys.env.getOrElse("QISABELLE_HOST", "localhost")
  override def port: Int          = sys.env.getOrElse("QISABELLE_PORT", "17000").toInt
  override def main(args: Array[String]): Unit = {
    println("QIsabelleServer starts listening.")  // s" on ${host}:${port}")
    super.main(args)
  }
}
