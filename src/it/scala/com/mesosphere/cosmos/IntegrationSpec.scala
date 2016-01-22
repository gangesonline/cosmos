package com.mesosphere.cosmos

import com.mesosphere.cosmos.endpoint.ListHandler
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import io.finch.test.ServiceIntegrationSuite
import org.scalatest.fixture

abstract class IntegrationSpec
  extends fixture.FlatSpec
  with ServiceIntegrationSuite
  with CosmosSpec {

  def createService: Service[Request, Response] = {
    val adminRouterUri = adminRouterHost
    val dcosClient = Services.adminRouterClient(adminRouterUri).get
    val adminRouter = new AdminRouter(adminRouterUri, dcosClient)

    // these two imports provide the implicit DecodeRequest instances needed to instantiate Cosmos
    import io.circe.generic.auto._
    import io.finch.circe._
    new Cosmos(
      PackageCache.empty,
      new MarathonPackageRunner(adminRouter),
      new UninstallHandler(adminRouter),
      new ListHandler(adminRouter, PackageCache.empty)
    ).service
  }

  protected[this] final override val servicePort: Int = port

}
