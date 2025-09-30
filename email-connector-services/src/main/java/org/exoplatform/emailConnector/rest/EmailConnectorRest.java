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

import java.io.InputStream;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailConnectorService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/emailConnector")
@Tag(name = "/email-connector/rest/emailConnector", description = "Manages Email Connector")
public class EmailConnectorRest {

  @Autowired
  private EmailConnectorService emailConnectorService;

  @PutMapping("activate/{isFeatureActive}")
  @Secured("administrators")
  @Operation(summary = "Activate email feature", method = "PUT", description = "This will activate email feature")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<String> activate(HttpServletRequest request,
                                         @Parameter(description = "Is feature active")
                                         @PathVariable("isFeatureActive")
                                         String isFeatureActive) {
    try {
      emailConnectorService.activateEmailFeature(isFeatureActive, request.getRemoteUser());
      return ResponseEntity.ok().build();
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping()
  @Secured("administrators")
  @Operation(summary = "Creates email connector", method = "POST", description = "This will create email connector")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public EmailConnector createEmailConnector(HttpServletRequest request, @RequestBody
  EmailConnector emailConnector) {
    try {
      return emailConnectorService.createEmailConnector(emailConnector, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PutMapping()
  @Secured("administrators")
  @Operation(summary = "Updates email connector identified by its id", method = "PUT", description = "This will update an existing email connector identified by its id")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void updateEmailConnector(HttpServletRequest request, @RequestBody
  EmailConnector emailConnector) {
    try {
      emailConnectorService.updateEmailConnector(emailConnector, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PatchMapping(path = "/{emailConnectorId}/{isEmailConnectorActive}")
  @Secured("administrators")
  @Operation(summary = "Activates email connector identified by its id", method = "PATCH", description = "This will activate or deactivate an existing email connector identified by its id")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void activateEmailConnector(HttpServletRequest request,
                                     @Parameter(description = "Email connector technical id to delete", required = true)
                                     @PathVariable("emailConnectorId")
                                     Long emailConnectorId,
                                     @Parameter(description = "Is email connector active", required = true)
                                     @PathVariable("isEmailConnectorActive")
                                     String isEmailConnectorActive) {
    try {
      emailConnectorService.activateEmailConnector(emailConnectorId, isEmailConnectorActive, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @DeleteMapping(path = "/{emailConnectorId}")
  @Secured("administrators")
  @Operation(summary = "Deletes an existing email connector identified by its id", method = "DELETE", description = "This will delete an existing email connector identified by its id")
  @ApiResponses(value = { @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "500", description = "Internal server error") })
  public void deleteEmailConnector(HttpServletRequest request,
                                   @Parameter(description = "Email connector technical id to delete", required = true)
                                   @PathVariable("emailConnectorId")
                                   Long emailConnectorId) {
    try {
      emailConnectorService.deleteEmailConnector(emailConnectorId, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping()
  @Secured("administrators")
  @Operation(summary = "Gets email connectors", method = "GET", description = "This will get email connectors")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public List<EmailConnector> getEmailConnectors(HttpServletRequest request) {
    return emailConnectorService.getEmailConnectors(request.getLocale());
  }

  @GetMapping("/active")
  @Secured("users")
  @Operation(summary = "Gets active email connectors", method = "GET", description = "This will get active email connectors")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public List<EmailConnector> getActiveEmailConnectors(HttpServletRequest request) {
    return emailConnectorService.getActiveEmailConnectors(request.getLocale(), request.getRemoteUser());
  }

  @GetMapping(path = "/illustration/{emailConnectorId}")
  @Secured("users")
  @Operation(summary = "Gets an email connector illustration by email connector id", method = "GET", description = "This will get an email connector illustration by email connector id")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "500", description = "Internal server error"),
      @ApiResponse(responseCode = "400", description = "Invalid query input"),
      @ApiResponse(responseCode = "404", description = "Resource not found") })
  public ResponseEntity<InputStreamResource> getEmailConnectorIllustration(HttpServletRequest request,
                                                                           @Parameter(description = "Email connector id", required = true)
                                                                           @PathVariable("emailConnectorId")
                                                                           long emailConnectorId) {
    EmailConnector emailConnector = emailConnectorService.getEmailConnector(emailConnectorId);
    if (emailConnector == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    try {
      InputStream stream = emailConnectorService.getEmailConnectorImageInputStream(emailConnectorId);
      if (stream == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      BodyBuilder builder = ResponseEntity.ok();
      return builder.contentType(MediaType.IMAGE_PNG).body(new InputStreamResource(stream));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PutMapping("/userEmailSetting")
  @Secured("users")
  @Operation(summary = "Sets user email setting", method = "PUT", description = "This will set user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void connectUserEmailSetting(HttpServletRequest request, @RequestBody
  UserEmailSetting userEmailSetting) {
    try {
      emailConnectorService.connectUserEmailSetting(userEmailSetting, request.getRemoteUser());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/userEmailSetting")
  @Secured("users")
  @Operation(summary = "Gets user email setting", method = "GET", description = "This will get user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public UserEmailSetting getUserEmailSetting(HttpServletRequest request) {
    return emailConnectorService.getUserEmailSetting(request.getRemoteUser());
  }

  @DeleteMapping("/userEmailSetting")
  @Secured("users")
  @Operation(summary = "Deletes user email setting", method = "DELETE", description = "This will delete user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void deleteUserEmailSetting(HttpServletRequest request) {
    try {
      emailConnectorService.deleteUserEmailSetting(request.getRemoteUser());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }
}
