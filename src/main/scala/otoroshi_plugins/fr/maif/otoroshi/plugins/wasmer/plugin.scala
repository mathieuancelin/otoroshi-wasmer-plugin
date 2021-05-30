package otoroshi_plugins.fr.maif.otoroshi.plugins.wasmer

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import java.nio.file.{Files, Path, Paths}

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class WasmerResponse extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-wasmer-response")

  private val pool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  private val scriptCache: Cache[String, ByteString] = Scaffeine()
    .recordStats()
    .expireAfterWrite(10.minutes)
    .maximumSize(100)
    .build()

  override def name: String = "Wasmer Response"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "WasmerResponse" -> Json.obj(
          "pages"   -> "1",
          "wasm" -> "base64 wasm script or URL to wasm script"
        )
      )
    )

  override def description: Option[String] =
    Some(s"""This plugin returns an http response from a WASM script
           |
           |This plugin can accept the following configuration
           |
           |```json
           |${defaultConfig.get.prettify}
           |```
    """.stripMargin)

  private val awaitingRequests = new TrieMap[String, Promise[Source[ByteString, _]]]()

  override def beforeRequest(ctx: BeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.putIfAbsent(ctx.snowflake, Promise[Source[ByteString, _]])
    funit
  }

  override def afterRequest(ctx: AfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.remove(ctx.snowflake)
    funit
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(ctx.snowflake).map(_.trySuccess(ctx.body))
    ctx.body
  }

  def getWasm(config: JsValue)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[ByteString] = {
    val wasm = config.select("wasm").asString
    if (wasm.startsWith("http://") || wasm.startsWith("https://")) {
      scriptCache.getIfPresent(wasm) match {
        case Some(script) => script.future
        case None => {
          env.Ws.url(wasm).withRequestTimeout(10.seconds).get().map { resp =>
            val body = resp.bodyAsBytes
            scriptCache.put(wasm, body)
            body
          }
        }
      }
    } else if (wasm.startsWith("file://")) {
      scriptCache.getIfPresent(wasm) match {
        case Some(script) => script.future
        case None => {
          val body = ByteString(Files.readAllBytes(Paths.get(wasm.replace("file://", ""))))
          scriptCache.put(wasm, body)
          body.future
        }
      }
    } else if (wasm.startsWith("base64://")) {
      ByteString(wasm.replace("base64://", "")).decodeBase64.future
    } else {
      ByteString(wasm).decodeBase64.future
    }
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = ctx.configFor("WasmerResponse")
    val pages = config.select("pages").asOpt[Int].getOrElse(0)
    getWasm(config).flatMap { wasm =>
      awaitingRequests
        .get(ctx.snowflake)
        .map { promise =>
          val bodySource: Source[ByteString, _] = Source
            .future(promise.future)
            .flatMapConcat(s => s)
          bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
            Future {
              val body = if (bodyRaw.isEmpty) JsNull else JsString(bodyRaw.utf8String)
              val req = ctx.otoroshiRequest
              val headersIn: Map[String, String] = req.headers
              val context = ByteString(Json.stringify(Json.obj(
                "query" -> req.uri.query().toMap,
                "method" -> req.method,
                "headers" -> headersIn,
                "body" -> body,
                "path" -> req.uri.path.toString(),
              )))
              logger.debug(s"context: ${context.utf8String}")
              Wasmer.script(wasm.toByteBuffer.array(), pages) { wasmerEnv =>
                Try {
                  val input = wasmerEnv.input_raw(context.toByteBuffer.array())
                  wasmerEnv.execFunction("handle_http_request", Seq(input))
                } match {
                  case Failure(ex) => {
                    logger.error(s"error from wasm", ex)
                    Left(Results.InternalServerError(Json.obj("err" -> "internal_server_error", "err_desc" -> ex.getMessage)))
                  }
                  case Success(output) => {
                    logger.debug(s"output from wasm: ${output.utf8String}")
                    val res = Json.parse(output.utf8String)
                    val status = (res \ "status").as[Int]
                    val headersRes = (res \ "headers").as[Map[String, String]]
                    val bodyRes = (res \ "body").as[JsValue] match {
                      case JsString(v) => v
                      case v => Json.stringify(v)
                    }
                    val contentType = headersRes.get("Content-Type").orElse(headersRes.get("content-type")).getOrElse("text/plain")
                    Left(
                      Results.Status(status)
                        .apply(bodyRes)
                        .as(contentType)
                        .withHeaders(headersRes.filterNot(_._1.toLowerCase() == "content-type").toSeq: _*)
                    )
                  }
                }
              }
            }(pool)
          }
        } getOrElse {
        Left(Results.InternalServerError(Json.obj("error" -> "no matching request end"))).future
      }
    }
  }
}