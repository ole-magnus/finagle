package com.twitter.finagle.tracing

import com.twitter.finagle.{Filter, Dtab, Service}
import com.twitter.util.{Await, Future}
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.{spy, verify, when, atLeastOnce}
import org.mockito.Matchers.any
import org.scalactic.source.Position
import org.scalatestplus.junit.AssertionsForJUnit
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Tag}
import scala.collection.JavaConverters._

class TracingFilterTest
    extends FunSuite
    with MockitoSugar
    with BeforeAndAfter
    with AssertionsForJUnit {

  val serviceName = "bird"
  val service = Service.mk[Int, Int](Future.value)
  val exceptingService = Service.mk[Int, Int]({ x =>
    Future.exception(new Exception("bummer"))
  })

  var tracer: Tracer = _
  var captor: ArgumentCaptor[Record] = _

  override def test(testName: String, testTags: Tag*)(f: => Any)(implicit pos: Position): Unit = {
    super.test(testName, testTags: _*) {
      tracer = spy(new NullTracer)
      when(tracer.isActivelyTracing(any[TraceId])).thenReturn(true)
      captor = ArgumentCaptor.forClass(classOf[Record])
      Trace.letTracer(tracer) { f }
    }
  }

  def record(filter: Filter[Int, Int, Int, Int]): Seq[Record] = {
    val composed = filter andThen service
    Await.result(composed(4))
    verify(tracer, atLeastOnce()).record(captor.capture())
    captor.getAllValues.asScala
  }

  def recordException(filter: Filter[Int, Int, Int, Int]): Seq[Record] = {
    val composed = filter andThen exceptingService
    intercept[Exception] { Await.result(composed(4)) }
    verify(tracer, atLeastOnce()).record(captor.capture())
    captor.getAllValues.asScala
  }

  def testAnnotatingTracingFilter(
    prefix: String,
    mkFilter: String => Filter[Int, Int, Int, Int]
  ): Unit = {
    test(s"$prefix: should trace service name") {
      val services = record(mkFilter("")) collect {
        case Record(_, _, Annotation.ServiceName(svc), _) => svc
      }
      assert(services == Seq(serviceName))
    }

    test(s"$prefix: should trace Finagle version") {
      val versions = record(mkFilter("1.2.3")) collect {
        case Record(_, _, Annotation.BinaryAnnotation(key, ver), _)
            if key == s"$prefix/finagle.version" =>
          ver
      }
      assert(versions == Seq("1.2.3"))
    }

    test(s"$prefix: should trace unknown Finagle version") {
      val versions = record(mkFilter("?")) collect {
        case Record(_, _, Annotation.BinaryAnnotation(key, ver), _)
            if key == s"$prefix/finagle.version" =>
          ver
      }
      assert(versions == Seq("?"))
    }

    def withDtab(dtab: Dtab) = Filter.mk[Int, Int, Int, Int] { (req, svc) =>
      Dtab.unwind {
        Dtab.local = dtab
        svc(req)
      }
    }

    test(s"$prefix: should trace Dtab.local") {
      val dtab = Dtab.read("/fox=>/spooky;/dana=>/starbuck")
      val dtabs = record(withDtab(dtab) andThen mkFilter("")) collect {
        case Record(_, _, Annotation.BinaryAnnotation(key, dtab), _)
            if key == s"$prefix/dtab.local" =>
          dtab
      }
      assert(dtabs == Seq(dtab.show))
    }

    test(s"$prefix: should not trace empty Dtab.local") {
      val dtabs = record(withDtab(Dtab.empty) andThen mkFilter("")) collect {
        case Record(_, _, Annotation.BinaryAnnotation(key, dtab), _)
            if key == s"$prefix/dtab.local" =>
          dtab
      }
      assert(dtabs.isEmpty)
    }
  }

  /*
   * Client tracing
   */

  def mkClient(v: String = "") =
    ClientTracingFilter
      .TracingFilter[Int, Int](serviceName, () => v).andThen(
        WireTracingFilter.TracingFilter[Int, Int](
          serviceName,
          "srv",
          Annotation.WireRecv,
          Annotation.WireSend,
          traceMetadata = false,
          () => v
        )
      )

  testAnnotatingTracingFilter("clnt", mkClient)

  test("clnt: send and then recv") {
    val annotations = record(mkClient()) collect {
      case Record(_, _, Annotation.ClientSend, _) => Annotation.ClientSend
      case Record(_, _, Annotation.ClientRecv, _) => Annotation.ClientRecv
    }
    assert(annotations == Seq(Annotation.ClientSend, Annotation.ClientRecv))
  }

  test("clnt: recv error") {
    val annotations = recordException(mkClient()) collect {
      case Record(_, _, Annotation.ClientSend, _) => Annotation.ClientSend
      case Record(_, _, Annotation.ClientRecv, _) => Annotation.ClientRecv
      case Record(_, _, a @ Annotation.ClientRecvError(_), _) => a
    }
    assert(
      annotations == Seq(
        Annotation.ClientSend,
        Annotation.ClientRecvError("java.lang.Exception: bummer"),
        Annotation.ClientRecv
      )
    )
  }

  /*
   * Server tracing
   */

  def mkServer(v: String = "") =
    ServerTracingFilter
      .TracingFilter[Int, Int](serviceName, () => v).andThen(
        WireTracingFilter.TracingFilter[Int, Int](
          serviceName,
          "srv",
          Annotation.WireRecv,
          Annotation.WireSend,
          traceMetadata = true,
          () => v
        )
      )

  testAnnotatingTracingFilter("srv", mkServer)

  test("srv: recv and then send") {
    val annotations = record(mkServer()) collect {
      case Record(_, _, Annotation.ServerRecv, _) => Annotation.ServerRecv
      case Record(_, _, Annotation.ServerSend, _) => Annotation.ServerSend
    }
    assert(annotations == Seq(Annotation.ServerRecv, Annotation.ServerSend))
  }
}
