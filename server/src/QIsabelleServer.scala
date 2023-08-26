package server

import scala.concurrent.duration.Duration

import de.unruh.isabelle.pure.{Theory, ToplevelState}
import de.unruh.isabelle.control.IsabelleMLException
import de.unruh.isabelle.mlvalue.MLValue
import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.mlvalue.Implicits._

case class QIsabelleRoutes()(implicit cc: castor.Context, log: cask.Logger) extends cask.Routes {
  var session: IsabelleSession = null

  def exceptionMsg(e: Throwable): String = {
    e.toString() + "\n" + e.getStackTrace().mkString("\n")
  }

  @cask.get("/")
  def hello() = {
    "Hello from QIsabelle"
  }

  @cask.postJson("/openIsabelleSession")
  def openIsabelleSession(workingDir: String, theoryPath: String, target: String): String = {
    println("openIsabelleSession 1: new IsabelleSession")
    try {
      session = new IsabelleSession(
        isabelleDir = os.Path("/home/isabelle/Isabelle/"),
        workingDir = os.Path(workingDir),
        theoryPath = os.Path(theoryPath),
        debug = true
      )
    } catch {
      case e: Throwable => {
        val msg = "Error creating IsabelleSession (probably in begin_theory): " + exceptionMsg(e)
        println(msg)
        return msg
      }
    }
    // return "success"

    try {
      println("openIsabelleSession 2: execute")
      val state = session.execute(session.parsedTheory.takeUntil(target, inclusive = false))
      println("proofState=" + state.proofStateDescription(session.isabelle))
      println("openIsabelleSession 3: register_tls")
      session.register_tls("default", state)
      println("openIsabelleSession 4: done.")
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
    r
  }

  @cask.postJson("/step")
  def step(state_name: String, action: String, new_state_name: String): ujson.Obj = {
    var timeout: Duration        = Duration(2000, "millisecond")
    val old_state: ToplevelState = session.retrieve_tls(state_name)
    var actual_action: String    = action
    var hammered: Boolean        = false

    // if (action.startsWith("normalhammer")) {
    //   val s: String = action.split("normalhammer").drop(1).mkString("").trim
    //   val add_names: List[String] = {
    //     if (s contains "<add>") {
    //       s.split("<add>")(1).split(",").toList
    //     } else List[String]()
    //   }
    //   val del_names: List[String] = {
    //     if (s contains "<del>") {
    //       s.split("<del>")(1).split(",").toList
    //     } else List[String]()
    //   }
    //   val partial_hammer = (state: ToplevelState, timeout: Int) =>
    //     session.normal_with_hammer(state, add_names, del_names, timeout)
    //   try {
    //     actual_action = hammer_actual_step(old_state, new_state_name, partial_hammer)
    //   } catch {
    //     case e: Throwable => {
    //       println("Error during hammer: " + e.toString())
    //       return ujson.Obj(
    //         "state_string" -> ("Error during hammer: " + e.toString()),
    //         "done"         -> false
    //       )
    //     }
    //   }
    //   hammered = true
    // }

    try {
      val new_state: ToplevelState = session.step(actual_action, old_state, timeout)
      // println("New state: " + session.getStateString(new_state))

      session.register_tls(name = new_state_name, tls = new_state)

      var state_string: String = new_state.proofStateDescription(session.isabelle)
      if (hammered) {
        state_string = s"(* $action *) $state_string"
      }
      var done: Boolean = (new_state.proofLevel(session.isabelle) == 0)
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

  def process_hammer_strings(hammer_string_list: List[String]): String = {
    val TRY_STRING: String         = "Try this:"
    val FOUND_PROOF_STRING: String = "found a proof:"
    val TIME_STRING1: String       = " ms)"
    val TIME_STRING2: String       = " s)"
    var found                      = false
    for (attempt_string <- hammer_string_list) {
      if (!found && (attempt_string contains TRY_STRING)) {
        found = true
        val parsed = attempt_string.split(TRY_STRING).drop(1).mkString("").trim
        if ((parsed contains TIME_STRING1) || (parsed contains TIME_STRING2)) {
          return parsed.split('(').dropRight(1).mkString("(").trim
        }
        return parsed
      } else if (!found && (attempt_string contains FOUND_PROOF_STRING)) {
        found = true
        val parsed =
          attempt_string.split(FOUND_PROOF_STRING).drop(1).mkString("").trim
        if ((parsed contains TIME_STRING1) || (parsed contains TIME_STRING2)) {
          return parsed.split('(').dropRight(1).mkString("(").trim
        }
        return parsed
      }
    }
    ""
  }

  def hammer_actual_step(
      old_state: ToplevelState,
      new_name: String,
      hammer_method: (ToplevelState, Int) => (Boolean, List[String])
  ): String = {
    // If found a sledgehammer step, execute it differently
    var raw_hammer_strings = List[String]()
    val actual_step: String = { // try {
      val total_result = hammer_method(old_state, 40000)
      // println(total_result)
      val success = total_result._1
      if (success) {
        // println("Hammer string list: " + total_result._2.mkString(" ||| "))
        val tentative_step = process_hammer_strings(total_result._2)
        // println("actual_step: " + tentative_step)
        tentative_step
      } else {
        val s = "Hammer failed:" + total_result._2.mkString(" ||| ")
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

//   @cask.post("/do-thing")
//   def doThing(request: cask.Request) = {
//     request.text().reverse
//   }

//   @cask.get("/user/:userName")
//   def showUserProfile(userName: String) = {
//     s"User $userName"
//   }

//   @cask.postJson("/form")  // Expects a dict with keys value1, value2.
//   def formEndpointObj(value1: String, value2: Seq[Int]) = {
//     ujson.Obj(
//       "value1" -> value1.value,
//       "value2" -> value2
//     )
//     // cask.Abort(401)
//   }

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
