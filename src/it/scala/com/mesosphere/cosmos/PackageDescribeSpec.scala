package com.mesosphere.cosmos

import cats.data.Xor
import cats.data.Xor.Right
import com.mesosphere.cosmos.http.EndpointHandler
import com.mesosphere.cosmos.model._
import com.netaporter.uri.Uri
import com.twitter.finagle.http._
import com.twitter.io.{Buf}
import com.twitter.finagle.{Http, Service}
import com.twitter.util._
import io.circe.parse._
import io.circe.generic.auto._
import io.circe.Json
import io.circe.syntax._
import org.scalatest.FreeSpec

final class PackageDescribeSpec extends FreeSpec with CosmosSpec {

  import IntegrationHelpers._
  import PackageDescribeSpec._

  "The package describe endpoint" - {
    "don't install if specified version is not found"/*TODO: Copy paste test name */ in {
      val _ = withTempDirectory { universeDir =>
        val universeCache = Await.result(UniversePackageCache(UniverseUri, universeDir))

        runService(packageCache = universeCache) { apiClient =>
          forAll (PackageDummyVersionsTable) { (packageName, packageVersion) =>
            apiClient.describeAndAssertError(
              packageName = packageName,
              status = Status.BadRequest,
              expectedMessage = s"Version [$packageVersion] of package [$packageName] not found",
              version = Some(packageVersion)
            )
          }
        }
      }
    }

    "can successfully describe helloworld from Universe" in {
      val _ = withTempDirectory { universeDir =>
        val universeCache = Await.result(UniversePackageCache(UniverseUri, universeDir))

        runService(packageCache = universeCache) { apiClient =>
          apiClient.describeHelloworld()
          apiClient.describeHelloworld(Some("0.1.0"))
        }
      }
    }

    "can successfully describe all versions from Universe" in {
      val _ = withTempDirectory { universeDir =>
        val universeCache = Await.result(UniversePackageCache(UniverseUri, universeDir))

        runService(packageCache = universeCache) { apiClient =>
          forAll (PackageVersionsTable) { (packageName, versions) =>
            apiClient.describeVersionsAndAssert(
              packageName=packageName,
              status=Status.Ok,
              content=versions.asJson
            )
          }
        }
      }
    }
  }

  private[this] def runService[A](
    dcosClient: Service[Request, Response] = Services.adminRouterClient(adminRouterHost).get,
    packageCache: PackageCache
  )(
    f: DescribeTestAssertionDecorator => Unit
  ): Unit = {
    // these two imports provide the implicit DecodeRequest instances needed to instantiate Cosmos
    import io.circe.generic.auto._
    import io.finch.circe._
    val service = new Cosmos(
      packageCache,
      new MarathonPackageRunner(adminRouter),
      EndpointHandler.const(UninstallResponse(Nil)),
      EndpointHandler.const(ListResponse(Nil))
    ).service
    val server = Http.serve(s":$servicePort", service)
    val client = Http.newService(s"127.0.0.1:$servicePort")

    try {
      f(new DescribeTestAssertionDecorator(client))
    } finally {
      Await.all(server.close(), client.close(), service.close())
    }
  }
}

private object PackageDescribeSpec extends CosmosSpec {

  private val UniverseUri = Uri.parse("https://github.com/mesosphere/universe/archive/cli-test-4.zip")

  private val PackageDummyVersionsTable = Table(
    ("package name", "version"),
    ("helloworld", "a.b.c"),
    ("cassandra", "foobar")
  )

  private val PackageVersionsTable = Table(
    ("package name", "versions"),
    ("helloworld", Map[String,String]("0.1.0" -> "0"))
  )

  val HelloworldPackageDef = PackageDefinition(
    packagingVersion = "2.0",
    name = "helloworld",
    version = "0.1.0",
    website = Some("https://github.com/mesosphere/dcos-helloworld"),
    maintainer = "support@mesosphere.io",
    description = "Example DCOS application package",
    preInstallNotes = Some("A sample pre-installation message"),
    postInstallNotes = Some("A sample post-installation message"),
    tags = List("mesosphere", "example", "subcommand")
  )

  val HelloworldResource = Resource()

  case class HelloworldPort(`type`: String, default: Int)
  case class HelloworldProperties(port: HelloworldPort)
  case class HelloworldConfig(`$schema`: String, `type`: String, properties: HelloworldProperties, additionalProperties: Boolean)

  val HelloworldConfigDef = HelloworldConfig(
    "http://json-schema.org/schema#",
    "object",
    HelloworldProperties(HelloworldPort("integer", 8080)),
    additionalProperties = false
  )

  case class HelloworldCommand(pip: List[String])
  val HelloworldCommandDef = HelloworldCommand(
    List("dcos<1.0", "git+https://github.com/mesosphere/dcos-helloworld.git#dcos-helloworld=0.1.0")
  )

  case class HelloworldDocker(image: String, network: String)
  case class HelloworldContainer(`type`: String, docker: HelloworldDocker)
  case class HelloworldMustache(
    id: String,
    cpus: Double,
    mem: Int,
    instances: Int,
    cmd: String,
    container: HelloworldContainer
  )

  val HelloworldMustacheDef = HelloworldMustache(
    id = "helloworld",
    cpus = 1.0,
    mem = 512,
    instances = 1,
    cmd = "python3 -m http.server {{port}}",
    container = HelloworldContainer("DOCKER", HelloworldDocker("python:3", "HOST"))
  )
}

private final class DescribeTestAssertionDecorator(apiClient: Service[Request, Response]) extends CosmosSpec {
  import PackageDescribeSpec._

  val DescribeEndpoint = "v1/package/describe"

  private[cosmos] def describeAndAssertError(
    packageName: String,
    status: Status,
    expectedMessage: String,
    version: Option[String] = None
  ): Unit = {
    val response = describeRequest(apiClient, DescribeRequest(packageName, version, None))
    assertResult(status)(response.status)
    val Right(errorResponse) = decode[ErrorResponse](response.contentString)
    assertResult(expectedMessage)(errorResponse.errors.head.message)
  }

  private[cosmos] def describeVersionsAndAssert(
    packageName: String,
    status: Status,
    content: Json
  ): Unit = {
    val response = describeRequest(apiClient, DescribeRequest(packageName, None, Some(true)))
    assertResult(status)(response.status)
    assertResult(Xor.Right(content))(parse(response.contentString))
  }

  private[cosmos] def describeHelloworld(version: Option[String] = None) = {
    val response = describeRequest(apiClient, DescribeRequest("helloworld", version, None))
    assertResult(Status.Ok)(response.status)
    val Right(packageInfo) = parse(response.contentString)

    val Right(packageJson) = packageInfo.cursor.get[PackageDefinition]("package")
    assertResult(HelloworldPackageDef)(packageJson)

    val Right(resourceJson) = packageInfo.cursor.get[Resource]("resource")
    assertResult(HelloworldResource)(resourceJson)

    val Right(configJson) = packageInfo.cursor.get[HelloworldConfig]("config")
    assertResult(HelloworldConfigDef)(configJson)

    val Right(commandJson) = packageInfo.cursor.get[HelloworldCommand]("command")
    assertResult(HelloworldCommandDef)(commandJson)
  }

  private[this] def describeRequest(
    apiClient: Service[Request, Response],
    describeRequest: DescribeRequest
  ): Response = {
    val request = requestBuilder(DescribeEndpoint)
      .buildPost(Buf.Utf8(describeRequest.asJson.noSpaces))
    Await.result(apiClient(request))
  }
}


