package com.mesosphere.cosmos.error

import com.mesosphere.cosmos.rpc
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.generic.semiauto.deriveEncoder

final case class RepositoryUriSyntax(
  repository: rpc.v1.model.PackageRepository,
  cause: String
) extends CosmosError {
  override def data: Option[JsonObject] = CosmosError.deriveData(this)
  override def message: String = {
    s"URI for repository [${repository.name}] has invalid syntax: ${repository.uri}"
  }
}

object RepositoryUriSyntax {
  implicit val encoder: Encoder[RepositoryUriSyntax] = deriveEncoder
}
