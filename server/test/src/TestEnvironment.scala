package server

import org.scalatest.funsuite.AnyFunSuite

import de.unruh.isabelle.control.IsabelleMLException

class TestEnvironment extends AnyFunSuite {
  val env = loadEnv(os.pwd / ".env");

  val isabelleDir = env.get("ISABELLE_DIR") map {
    os.FilePath(_).resolveFrom(os.pwd)
  } getOrElse (os.pwd / "Isabelle2023")
  println(s"isabelleDir = $isabelleDir")

  val afpDir = os.FilePath(env("AFP_DIR")).resolveFrom(os.pwd)
  println(s"afpDir = $afpDir")
  val afpThysDir = afpDir / "thys"

  def withIsabelleSession(sessionName: String, sessionRoots: Seq[os.Path])(
      f: IsabelleSession => Unit
  ) = {
    val session = new IsabelleSession(
      isabelleDir = isabelleDir,
      sessionName = sessionName,
      sessionRoots = sessionRoots,
      workingDir = os.pwd,
      debug = true
    )
    try
      f(session)
    catch {
      case e: IsabelleMLException => {
        e.getMessage() // Await exception details before closing Isabelle.
        throw e        // Rethrow exception.
      }
    } finally
      session.close()
  }

  def withTheory(theoryPath: os.Path)(f: (IsabelleSession, ParsedTheory) => Unit) = {
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

  def loadEnv(path: os.Path): Map[String, String] = {
    os.read
      .lines(path)
      .map(line => {
        val parts = line.split("=")
        (parts(0), parts(1))
      })
      .toMap
  }
}
