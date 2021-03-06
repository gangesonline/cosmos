package com.mesosphere.universe.v2.model

case class Assets(
  uris: Option[Map[String, String]], // GitHub issue #58
  container: Option[Container]
)
