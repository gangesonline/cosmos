package com.mesosphere.cosmos.model.v1

import com.mesosphere.universe.v2.model.{Command, PackageDetails, Resource}
import io.circe.JsonObject

case class DescribeResponse(
  `package`: PackageDetails,
  marathonMustache: String,
  command: Option[Command] = None,
  config: Option[JsonObject] = None,
  resource: Option[Resource] = None
)