package server

import de.unruh.isabelle.control.IsabelleMLException

class SledgehammerTests extends TestEnvironment {
  test("hammer with added name") {
    var lemma = """
      lemma permsI [Pure.intro]:
        assumes "bij f" and "MOST x. f x = x"
        shows "f \<in> perms"
    """
    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Adhoc_Overloading_Examples.thy") {
      session =>
        implicit val isabelle = session.isabelle

        var state = session.execute(
          session.parsedTheory.takeUntil(
            lemma,
            // "lemma perms_imp_bij: \"f \\<in> perms \\<Longrightarrow> bij f\"",
            inclusive = true
          ),
          nDebug = 10000
        )
        state = session.step("using assms", state)
        // state = session.step("proof (auto simp: perms_def)", state)
        val (outcome, proofTextOrMsg) =
          Sledgehammer.run(state, addedNames = List("MOST_iff_finiteNeg"))
        assert(outcome == Sledgehammer.Outcomes.Some)
        assert(proofTextOrMsg.startsWith("by "))
        // "by (simp add: MOST_iff_finiteNeg perms_def)"
        state = session.step(proofTextOrMsg, state)
        assert(state.isTheoryMode && state.proofStateDescription == "")
        // state = session.step("qed  (metis MOST_iff_finiteNeg)", state)
        // assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }

  test("hammer simple") {
    var lemma = """
      lemma effect_to_assignments_i:
        assumes "as = effect_to_assignments op"
        shows "as = (map (\<lambda>v. (v, True)) (add_effects_of op) @ map (\<lambda>v. (v, False)) (delete_effects_of op))"
    """
    withIsabelle(afpDir / "Verified_SAT_Based_AI_Planning" / "STRIPS_Semantics.thy") { session =>
      implicit val isabelle = session.isabelle

      var state = session.execute(
        session.parsedTheory.takeUntil(lemma, inclusive = true),
        nDebug = 2
      )
      val (outcome, proofTextOrMsg) = Sledgehammer.run(state)
      assert(outcome == Sledgehammer.Outcomes.Some)
      assert(proofTextOrMsg.startsWith("by "))
      // "by (simp add: assms effect__strips_def effect_to_assignments_def)"
      state = session.step(proofTextOrMsg, state)
      assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }

  test("hammer foo") {
    var firstLemma  = """
      lemma uniqify_xlate:
        "bsmap (uniqify q) = SkewBinomialHeapStruc.uniqify (bsmap q)"
    """
    var firstProof  = """
      by (cases q) (simp_all add: ins_xlate)
    """
    var secondLemma = """
      lemma meldUniq_xlate:
        "bsmap (meldUniq q q') = SkewBinomialHeapStruc.meldUniq (bsmap q) (bsmap q')"
    """
    var secondProof = """
      apply (induct q q' rule: meldUniq.induct)
      apply (auto simp add: link_xlate proj_xlate uniqify_xlate ins_xlate)
      done
    """
    var hammerLemma = """
      lemma meld_xlate:
        "bsmap (meld q q') = SkewBinomialHeapStruc.meld (bsmap q) (bsmap q')"
    """
    // by (simp add: meld_def meldUniq_xlate uniqify_xlate SkewBinomialHeapStruc.meld_def)

    withIsabelle(afpDir / "Binomial-Heaps" / "SkewBinomialHeap.thy") { session =>
      implicit val isabelle = session.isabelle

      var state = session.execute(
        session.parsedTheory.takeUntil(firstLemma, inclusive = false),
        nDebug = 2
      )

      state = session.step(firstLemma, state)
      state = session.step(firstProof, state)

      if (true) {
        var stateBad = session.step(hammerLemma, state)
        assert(stateBad.isProofMode && stateBad.proofStateDescription.contains("(1 subgoal"))
        val (outcome, proofTextOrMsg) = Sledgehammer.run(stateBad)
        assert(outcome == Sledgehammer.Outcomes.Timeout)
      }

      state = session.step(secondLemma, state)
      state = session.step(secondProof, state)
      state = session.step(hammerLemma, state)

      val (outcome, proofTextOrMsg) = Sledgehammer.run(state)
      assert(outcome == Sledgehammer.Outcomes.Some)
      assert(proofTextOrMsg.startsWith("by "))
      state = session.step(proofTextOrMsg, state)
      assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }
}
