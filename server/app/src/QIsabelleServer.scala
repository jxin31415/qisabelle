package app

import de.unruh.isabelle.pure.{Theory, ToplevelState}
import de.unruh.isabelle.control.IsabelleMLException
import de.unruh.isabelle.mlvalue.MLValue
import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.mlvalue.Implicits._

case class QIsabelleRoutes()(implicit cc: castor.Context,
                       log: cask.Logger) extends cask.Routes{
  var pisaos: MiniPisaOS = null

  @cask.get("/")
  def hello() = {
    "Hello World!"
  }

  @cask.postJson("/initializePisaOS")
  def initializePisaOS(workingDir: String, theoryPath: String): String = {
    println("initializePisaOS 1: new PisaOS")
    try {
      pisaos = new MiniPisaOS(
        path_to_isa_bin = "/home/isabelle/Isabelle/bin/isabelle",
        working_directory = "/afp/" + workingDir,
        path_to_file = "/afp/" + theoryPath,
        debug = true,
      )
    } catch {
      case e: Throwable => return ("error during init of PisaOS (probably in begin_theory): " + e.toString())
    }
    println("initializePisaOS 2: step_to_transition_text")
    // stand_in_thy = pisaos.thy1.mlValue
    // stand_in_tls = pisaos.copy_tls
    pisaos.step_to_transition_text("end", after=false)
    println("initializePisaOS 3: top_level_state_map +=")
    pisaos.top_level_state_map += ("default" -> pisaos.copy_tls)
    println("initializePisaOS 4: done.")
    "success"
  }

  @cask.postJson("/exitPisaOS")
  def exitPisaOS() = {
    var r = pisaos.exit()
    pisaos = null
    r
  }

  @cask.postJson("/step")
  def step(state_name: String, action: String, new_state_name: String): ujson.Obj = {
    var TIMEOUT: Int = 30000
    val old_state: ToplevelState = pisaos.retrieve_tls(state_name)
    var actual_action: String = action
    var hammered: Boolean = false

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
      val partial_hammer = (state: ToplevelState, timeout: Int) =>
        pisaos.normal_with_hammer(state, add_names, del_names, timeout)
      try {
        actual_action = hammer_actual_step(old_state, new_state_name, partial_hammer)
      } catch {
        case e: Throwable => return ujson.Obj(
          "state_string" -> ("error during hammer: " + e.toString()),
          "done" -> false
        )
      }
      hammered = true
    }

    try {
      val new_state: ToplevelState = pisaos.step(actual_action, old_state, TIMEOUT)
      // println("New state: " + pisaos.getStateString(new_state))

      pisaos.register_tls(name = new_state_name, tls = new_state)

      var state_string: String = pisaos.getStateString(new_state)
      if (hammered) {
        state_string = s"$action <hammer> ${state_string}"
      }
      var done: Boolean = (pisaos.getProofLevel(new_state) == 0)
      ujson.Obj(
          "state_string" -> state_string,
          "done" -> done
      )
    } catch {
      case e: Throwable => return ujson.Obj(
        "state_string" -> ("error during step: " + e.toString()),
        "done" -> false
      )
    }
  }


  def process_hammer_strings(hammer_string_list: List[String]): String = {
    val TRY_STRING: String = "Try this:"
    val FOUND_PROOF_STRING: String = "found a proof:"
    val TIME_STRING1: String = " ms)"
    val TIME_STRING2: String = " s)"
    var found = false
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
    val actual_step: String =
      try {
        val total_result = hammer_method(old_state, 40000)
        // println(total_result)
        val success = total_result._1
        if (success) {
          // println("Hammer string list: " + total_result._2.mkString(" ||| "))
          val tentative_step = process_hammer_strings(total_result._2)
          // println("actual_step: " + tentative_step)
          tentative_step
        } else {
          "error"
        }
      } catch {
        case e: Exception => {
          println("Exception while trying to run sledgehammer: " + e.getMessage)
          e.getMessage
        }
      }
    // println(actual_step)
    assert(actual_step.trim.nonEmpty)
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

object QISabelleServer extends cask.Main{
  val allRoutes = Seq(QIsabelleRoutes())
  override def debugMode: Boolean = true
  override def host: String = sys.env("QISABELLE_HOST")
  override def port: Int = sys.env("QISABELLE_PORT").toInt
  override def main(args: Array[String]): Unit = {
    println(s"QIsabelleServer starting on ${host}:${port} ...")
    super.main(args)
  }
}
