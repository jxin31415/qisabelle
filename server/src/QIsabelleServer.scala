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
  val perTransitionTimeout: Duration = 10.seconds
  val executionTimeout: Duration  = Duration.Inf // Does not include time of running sledgehammer.
  val softHammerTimeout: Duration = 30.seconds
  val midHammerTimeout: Duration  = 35.seconds
  val hardHammerTimeout: Duration = 40.seconds

  var session: IsabelleSession   = null
  var parsedTheory: ParsedTheory = null

  /** Remembering states by name for the API. */
  var stateMap: Map[String, ToplevelState] = null

  /** A dummy endpoint to just check if the server is running. */
  @cask.get("/")
  def hello() = {
    "Hello from QIsabelle"
  }

  /** Start an Isabelle process and load a given theory up until a given transition.
    *
    * @param workingDir
    *   The working directory of the Isabelle process. Doesn't influence anything important anymore;
    *   it would be used for resolving relative theory paths for theories that are not found
    *   pre-built in the session heap, but we throw an exception if that happens anyway. You can use
    *   the directory containing the theory file, for example.
    * @param theoryPath
    *   Path to .thy file to partially load.
    * @param target
    *   Transition (as Isar code) at which to stop loading the theory.
    *
    * Creates a state named "default" which executes the theory up to `target` (exclusive).
    *
    * @return
    *   {"success": "success"} or {"error": str, "traceback": str}
    */
  @cask.postJson("/openIsabelleSession")
  def openIsabelleSession(workingDir: String, theoryPath: String, target: String): ujson.Obj = {
    println("openIsabelleSession 1: new IsabelleSession")
    try {
      val sessionName  = IsabelleSession.guessSessionName(os.Path(theoryPath))
      val sessionRoots = IsabelleSession.guessSessionRoots(os.Path(theoryPath))

      session = new IsabelleSession(
        isabelleDir = os.Path("/home/isabelle/Isabelle/"),
        sessionName = sessionName,
        sessionRoots = sessionRoots,
        workingDir = os.Path(workingDir),
        debug = true
      )
      implicit val isabelle = session.isabelle
      parsedTheory = new ParsedTheory(os.Path(theoryPath), sessionName, debug = true)

      println("openIsabelleSession 2: execute")
      val state = parsedTheory.executeUntil(target, inclusive = false, nDebug = 3)
      stateMap = Map()
      stateMap += ("default" -> state)
      println("openIsabelleSession 3: done.")
      return ujson.Obj("success" -> "success")
    } catch {
      case e: Throwable => { closeIsabelleSession(); exceptionJson(e) }
    }
  }

  /** Stop the Isabelle process and clear the state map and theory from memory.
    *
    * @return
    *   {"success": "Closed"} or {"error": str, "traceback": ""}
    */
  @cask.postJson("/closeIsabelleSession")
  def closeIsabelleSession(): ujson.Obj = {
    if (session == null)
      return ujson.Obj("error" -> "Already closed", "traceback" -> "")
    session.close()
    session = null
    parsedTheory = null
    stateMap = null
    return ujson.Obj("success" -> "Closed")
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
    *     - proofState: like "proof (prove) goal (1 subgoal): ..." (empty if not in proof mode).
    *     - proofDone: true if proof level is 0 (equivalently: not in proof nor skipped-proof mode).
    *   - On error: {"error": str, "traceback": str}
    */
  @cask.postJson("/execute")
  def execute(stateName: String, isarCode: String, newStateName: String): ujson.Obj = {
    val state: ToplevelState = stateMap(stateName)
    implicit val isabelle    = session.isabelle
    implicit val ec          = session.ec

    try {
      val newState: ToplevelState =
        IsabelleSession.parseAndExecute(
          isarCode,
          state,
          perTransitionTimeout = perTransitionTimeout,
          totalTimeout = executionTimeout,
          debug = true
        )
      stateMap += (newStateName -> newState)

      return ujson.Obj(
        "proofState" -> newState.proofStateDescription,
        "proofDone"  -> (newState.proofLevel == 0)
      )
    } catch {
      case e: Throwable => exceptionJson(e)
    }
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
    val state: ToplevelState = stateMap(stateName)
    implicit val isabelle    = session.isabelle

    try {
      val proof = Sledgehammer.findProofOrThrow(
        state,
        addedFacts = addedFacts,
        deletedFacts = deletedFacts,
        softTimeout = softHammerTimeout,
        midTimeout = midHammerTimeout,
        hardTimeout = hardHammerTimeout
      )
      return ujson.Obj("proof" -> proof)
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
    println(s"QIsabelleServer starts listening on ${host}:${port}")
    super.main(args)
  }
}
