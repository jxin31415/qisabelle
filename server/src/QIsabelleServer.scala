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
  var session: IsabelleSession   = null
  var parsedTheory: ParsedTheory = null
  // Remembering states by name for the API.
  var stateMap: Map[String, ToplevelState] = null

  // A dummy endpoint to just check if the server is running.
  @cask.get("/")
  def hello() = {
    "Hello from QIsabelle"
  }

  @cask.postJson("/openIsabelleSession")
  def openIsabelleSession(workingDir: String, theoryPath: String, target: String): String = {
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
      stateMap = Map()
    } catch {
      case e: Throwable => {
        val msg = "Error creating IsabelleSession (probably in begin_theory): " + exceptionMsg(e)
        println(msg)
        return msg
      }
    }

    try {
      println("openIsabelleSession 2: execute")
      val state = parsedTheory.executeUntil(target, inclusive = false, nDebug = 3)
      stateMap += ("default" -> state)
      println("openIsabelleSession 3: done.")
      return "success"
    } catch {
      case e: Throwable => {
        val msg = "Error in session.execute: " + exceptionMsg(e)
        println(msg)
        return msg
      }
    }
  }

  @cask.postJson("/closeIsabelleSession")
  def closeIsabelleSession() = {
    var r = session.close()
    session = null
    parsedTheory = null
    stateMap = null
    r
  }

  @cask.postJson("/step")
  def step(state_name: String, action: String, new_state_name: String): ujson.Obj = {
    var timeout: Duration        = 2.seconds
    val old_state: ToplevelState = stateMap(state_name)
    var actual_action: String    = action
    var hammered: Boolean        = false

    if (action.startsWith("normalhammer")) {
      val s: String = action.split("normalhammer").drop(1).mkString("").trim
      val add_names: List[String] = {
        if (s contains "<add>") {
          s.split("<add>")(1).split(",").toList
        } else List[String]()
      }
      val del_names: List[String] = {
        if (s contains "<del>") {
          s.split("<del>")(1).split(",").toList
        } else List[String]()
      }
      val partial_hammer = (state: ToplevelState, timeout: Duration) => {
        implicit val isabelle = session.isabelle
        Sledgehammer.run(state, add_names, del_names, timeout)
      }
      try {
        actual_action = hammer_actual_step(old_state, new_state_name, partial_hammer)
      } catch {
        case e: Throwable => {
          println("Error during hammer: " + e.toString())
          return ujson.Obj(
            "state_string" -> ("Error during hammer: " + e.toString()),
            "done"         -> false
          )
        }
      }
      hammered = true
    }

    try {
      implicit val isabelle: Isabelle = session.isabelle
      val new_state: ToplevelState    = IsabelleSession.step(actual_action, old_state, timeout)
      // println("New state: " + session.getStateString(new_state))

      stateMap += (new_state_name -> new_state)

      var state_string: String = new_state.proofStateDescription
      if (hammered) {
        state_string = s"(* $action *) $state_string"
      }
      var done: Boolean = (new_state.proofLevel == 0)
      ujson.Obj(
        "state_string" -> state_string,
        "done"         -> done
      )
    } catch {
      case e: Throwable => {
        println(("Error during step: " + e.toString()))
        return ujson.Obj(
          "state_string" -> ("Error during step: " + e.toString()),
          "done"         -> false
        )
      }
    }
  }

  def hammer_actual_step(
      old_state: ToplevelState,
      new_name: String,
      hammer_method: (
          ToplevelState,
          Duration
      ) => (Sledgehammer.Outcomes.Outcome, String)
  ): String = {
    // If found a sledgehammer step, execute it differently
    var raw_hammer_strings = List[String]()
    val actual_step: String = { // try {
      val (outcome, proof_text_or_msg) =
        hammer_method(old_state, 60.seconds)
      if (outcome == Sledgehammer.Outcomes.Some) {
        proof_text_or_msg
      } else {
        val s = "Hammer failed:" + proof_text_or_msg
        println(s)
        throw new Exception(s)
      }
      // } catch {
      //   case e: Exception => {
      //     println("Exception while trying to run sledgehammer: " + e.getMessage)
      //     "Error: " + e.getMessage
      //   }
    }
    // println(actual_step)
    assert(actual_step.trim.nonEmpty, "Empty hammer string")
    actual_step
  }

// === Examples of parameterized HTTP endpoints in cask. ===
//   @cask.post("/do-thing")
//   def doThing(request: cask.Request) = {
//     request.text().reverse
//   }
//
//   @cask.get("/user/:userName")
//   def showUserProfile(userName: String) = {
//     s"User $userName"
//   }
//
//   @cask.postJson("/form")  // Expects a dict with keys value1, value2.
//   def formEndpointObj(value1: String, value2: Seq[Int]) = {
//     ujson.Obj(
//       "value1" -> value1.value,
//       "value2" -> value2
//     )
//     // cask.Abort(401)
//   }

  def exceptionMsg(e: Throwable): String = {
    e.toString() + "\n" + e.getStackTrace().mkString("\n")
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
