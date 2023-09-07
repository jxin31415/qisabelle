package server

import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException, blocking}
import scala.util.matching.Regex

import de.unruh.isabelle.control.{Isabelle, IsabelleMLException, OperationCollection}
import de.unruh.isabelle.mlvalue.{MLValue, MLValueWrapper}
import de.unruh.isabelle.pure.{Context, Position, Theory, TheoryHeader, ToplevelState, Transition}
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits.theoryConverter

import Sledgehammer.Ops

object Sledgehammer extends OperationCollection {
  object Outcomes extends Enumeration {
    type Outcome = Value
    val Some, Timeout, ResourcesOut, Unknown, None = Value
    def fromMLString(s: String): Outcome = {
      s match {
        case "some"          => Some         // Success, found some tentative proof.
        case "timeout"       => Timeout      // Timeout.
        case "resources_out" => ResourcesOut // Out of memory, too many symbols used, etc.
        case "none"          => None         // Nothing found, or no subgoals.
        case "unknown"       => Unknown      // Error.
        case _               => throw new Exception("Unexpected Sledgehammer outcome: " + s)
      }
    }
  }

  /** Run Slegehammer (ML `run_sledgehammer`) on a state to try solving the first goal.
    *
    * @param state
    *   A state (in proof mode).
    * @param addedFacts
    *   A list of fact names strongly suggested to Sledgehammer.
    * @param deletedFacts
    *   A list of fact names forbidden for Sledgehammer.
    * @param softTimeout
    *   This is a suggestion for sledgehammer; it must be significantly lower than midTimeout to
    *   give provers a chance of returning. The provers will actually run for ~10+s longer. Note
    *   that the provers may actually succeed sooner if you give them a smaller timeout.
    * @param midTimeout
    *   This is an ML timeout; if not given, the ML process would continue running provers even when
    *   scala timed-out and discarder the result handle. Not very precise, it will actually take a
    *   few seconds longer. Make this equal to hardTimeout, or smaller to avoid waiting on ML the
    *   next time scala calls it. It's not bulletproof though, stuff in the ML process may continue
    *   after it timed out.
    * @param hardTimeout
    *   This is a hard, precise timeout (but it doesn't kill the ML prover processes).
    * @return
    *   (outcome, message), where message is an Isar proof if outcome is success ("Some"). The proof
    *   is not guaranteed to be correct.
    */
  def run(
      state: ToplevelState,
      addedFacts: List[String] = List(),
      deletedFacts: List[String] = List(),
      softTimeout: Duration = 30.seconds,
      midTimeout: Duration = 35.seconds,
      hardTimeout: Duration = 40.seconds,
      debug: Boolean = true
  )(implicit isabelle: Isabelle): (Outcomes.Outcome, String) = {
    if (debug) println("Hammer: start")
    val start = System.currentTimeMillis()
    val (outcomeString, message): (String, String) = {
      try {
        Await.result(
          Ops
            .runSledgehammer(
              state,
              addedFacts,
              deletedFacts,
              softTimeout.toMillis.max(1),
              midTimeout.toMillis.max(1)
            )
            .retrieve,
          hardTimeout
        )
      } catch {
        case e: TimeoutException => {
          ("timeout", "Hard timeout exceeded: " + e.getMessage())
          // "Future timed out after [20000 milliseconds]":
        }
        case e: IsabelleMLException => {
          if (e.getMessage() contains "Timeout")
            ("timeout", "Mid timeout exceeded: " + e.getMessage()) // "Timeout after 22.012s"
          else
            throw e
        }
      }
    }

    val outcome = Outcomes.fromMLString(outcomeString)
    val proofOrMessage = {
      if (outcome == Outcomes.Some) hammerMessageToProofText(message) else message
    }
    if (debug) println("Hammer: time=" + ((System.currentTimeMillis() - start) / 1000.0) + "s")
    if (debug) println("Hammer: outcome=" + outcome)
    if (debug) println("Hammer: proof/msg=" + proofOrMessage)
    return (outcome, proofOrMessage)
  }

  /** Same as run(), but always returns a proof, or throws and exception.
    *
    * The proof is not guaranteed to be correct.
    */
  def findProofOrThrow(
      state: ToplevelState,
      addedFacts: List[String] = List(),
      deletedFacts: List[String] = List(),
      softTimeout: Duration = 30.seconds,
      midTimeout: Duration = 35.seconds,
      hardTimeout: Duration = 40.seconds,
      debug: Boolean = true
  )(implicit isabelle: Isabelle): String = {
    val (outcome, proofOrMessage) =
      run(state, addedFacts, deletedFacts, softTimeout, midTimeout, hardTimeout, debug)
    outcome match {
      case Outcomes.Some =>
        if (proofOrMessage.trim.nonEmpty) proofOrMessage
        else throw new Exception("Sledgehammer returned empty proof")
      case Outcomes.Timeout =>
        throw new TimeoutException("Sledgehammer timeout: " + proofOrMessage)
      case Outcomes.ResourcesOut =>
        throw new Exception("Sledgehammer out of resources: " + proofOrMessage)
      case Outcomes.None =>
        throw new Exception("Sledgehammer no proof found: " + proofOrMessage)
      case Outcomes.Unknown =>
        throw new Exception("Sledgehammer error: " + proofOrMessage)
    }
  }

  protected val hammerPreplayTime: Regex = "\\([0-9.,]+\\s+m?s\\)$".r

  protected def hammerMessageToProofText(hammerMessage: String): String = {
    val s = hammerPreplayTime.replaceAllIn(hammerMessage.trim, "").trim
    if (s contains "Try this:")
      s.stripPrefix("Try this:").trim
    else if (s contains "found a proof:")
      s.split("found a proof:").drop(1).mkString("").trim
    else
      throw new Exception("Could not parse hammer message: " + hammerMessage)
  }

  protected final class Ops(implicit isabelle: Isabelle) {
    import MLValue.compileFunction

    // The theory here does not affect what facts sledgehammer sees.
    lazy val theoryForHammer        = Theory("HOL.List")
    lazy val mlSledgehammer: String = theoryForHammer.importMLStructureNow("Sledgehammer")
    lazy val mlSledgehammerCommands: String =
      theoryForHammer.importMLStructureNow("Sledgehammer_Commands")
    lazy val mlSledgehammerProver: String =
      theoryForHammer.importMLStructureNow("Sledgehammer_Prover")

    lazy val runSledgehammer =
      compileFunction[ToplevelState, List[String], List[String], Long, Long, (String, String)](
        s"""fn (state, adds, dels, softTimeout, midTimeout) =>
         |    let
         |       fun as_ref_and_token_list (name) = (Facts.named name, []);
         |       val adds_ref = map as_ref_and_token_list adds;
         |       val dels_ref = map as_ref_and_token_list dels;
         |       val overrides = {add=adds_ref, del=dels_ref, only=false};
         |       fun go (state) =
         |          let
         |             val proof_state = Toplevel.proof_of state;
         |             val params = ${mlSledgehammerCommands}.default_params (Toplevel.theory_of state)
         |                [("provers", "cvc5 vampire verit e spass z3 zipperposition"),
         |                 (   "timeout", Value.print_int ( Real.ceil ((Real.fromInt softTimeout) / 1000.0) )   ),
         |                 ("verbose","true")];
         |             val results = ${mlSledgehammer}.run_sledgehammer
         |                params ${mlSledgehammerProver}.Normal NONE 1 overrides proof_state;
         |             val (is_outcome_SH_Some, (outcome, message)) = results;
         |           in
         |             (${mlSledgehammer}.short_string_of_sledgehammer_outcome outcome, YXML.content_of message)
         |           end;
         |    in
         |      Timeout.apply (Time.fromMilliseconds midTimeout) go (state) end
         |""".stripMargin
      )
  }
  override protected def newOps(implicit isabelle: Isabelle): Ops = new Ops()
}
