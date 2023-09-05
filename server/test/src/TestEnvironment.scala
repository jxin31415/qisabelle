package server

import org.scalatest.funsuite.AnyFunSuite

import de.unruh.isabelle.control.IsabelleMLException

class TestEnvironment extends AnyFunSuite {
  val afpDir      = os.Path("/home/mwrochna/projects/play/afp-2023-03-16/thys/")
  val isabelleDir = os.Path("/home/mwrochna/projects/play/Isabelle2022")
  // "/home/isabelle/Isabelle/" in container

  def withIsabelle(theoryPath: os.Path)(f: (IsabelleSession, ParsedTheory) => Unit) = {
    // val port = sys.env.getOrElse("QISABELLE_PORT", "17000").toInt
    val session = new IsabelleSession(
      isabelleDir = isabelleDir,
      sessionName = IsabelleSession.guessSessionName(theoryPath),
      sessionRoots = IsabelleSession.guessSessionRoots(theoryPath),
      workingDir = theoryPath / os.up,
      debug = true
    )
    implicit val isabelle = session.isabelle
    val parsedTheory      = new ParsedTheory(theoryPath, session.sessionName, debug = true)
    try
      f(session, parsedTheory)
    catch {
      case e: IsabelleMLException => {
        e.getMessage() // Await exception details before closing Isabelle.
        throw e        // Rethrow exception.
      }
    } finally
      session.close()
  }

  def exceptionMsg(e: Throwable): String = {
    e.toString() + "\n" + e.getStackTrace().mkString("\n")
  }
}
