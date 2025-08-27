/**
 * Copyright (C) 2025 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.rest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/emailConnector")
@Tag(name = "/email-connector/rest/emailConnector", description = "Manages Email Connector")
public class EmailConnectorRest {

  public static final String EMAIL_CONNECTOR_FEATURE = "emailConnector";

  private static final Log   LOG                     = ExoLogger.getLogger(EmailConnectorRest.class);

  @Autowired
  private ExoFeatureService  featureService;

  @PutMapping("activate/{isFeatureActive}")
  @Secured("administrators")
  @Operation(summary = "Activate email connector feature", method = "PUT", description = "This will activate email connector feature")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<String> activate(@Parameter(description = "Is feature active")
  @PathVariable("isFeatureActive")
  String isFeatureActive) {
    try {
      boolean isFeatureActiveBool = Boolean.parseBoolean(isFeatureActive);
      featureService.saveActiveFeature(EMAIL_CONNECTOR_FEATURE, isFeatureActiveBool);
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      LOG.error("Error when enabling/disabling email connector feature", e);
      return ResponseEntity.internalServerError().build();
    }
  }
}
