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

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/user-email-setting")
@Tag(name = "/email-connector/rest/user-email-setting", description = "Manages User Email Setting")
public class UserEmailSettingRest {

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @PutMapping()
  @Secured("users")
  @Operation(summary = "Sets user email setting", method = "PUT", description = "This will set user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void connectUserEmailSetting(HttpServletRequest request,
                                      @Parameter(description = "Broadcast email box cleanup event", required = true)
                                      @RequestParam(name = "broadcast", defaultValue = "true")
                                      boolean broadcast,
                                      @RequestBody
                                      UserEmailSetting userEmailSetting) {
    try {
      userEmailSettingService.connectUserEmailSetting(userEmailSetting, request.getRemoteUser(), broadcast);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping()
  @Secured("users")
  @Operation(summary = "Gets user email setting", method = "GET", description = "This will get user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public UserEmailSetting getUserEmailSetting(HttpServletRequest request) {
    return userEmailSettingService.getUserEmailSetting(request.getRemoteUser());
  }

  @PutMapping("/preferences")
  @Secured("users")
  @Operation(summary = "Updates the user's email notification / default-view preferences",
             method = "PUT",
             description = "Updates only the notification categories and default category view, without reconnecting the mailbox")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"), })
  public void updateEmailPreferences(HttpServletRequest request,
                                     @RequestBody
                                     UserEmailSetting userEmailSetting) {
    userEmailSettingService.updateEmailPreferences(request.getRemoteUser(),
                                                   userEmailSetting.getNotifyAllCategories(),
                                                   userEmailSetting.getNotifyCategories(),
                                                   userEmailSetting.getDefaultCategoryView());
  }

  @DeleteMapping()
  @Secured("users")
  @Operation(summary = "Deletes user email setting", method = "DELETE", description = "This will delete user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void deleteUserEmailSetting(HttpServletRequest request) {
    try {
      userEmailSettingService.deleteUserEmailSetting(request.getRemoteUser());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/connectors")
  @Secured("users")
  @Operation(summary = "Gets user active email connectors", method = "GET", description = "This will get active email connectors")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public List<EmailConnector> getUserEmailConnectors(HttpServletRequest request) {
    return userEmailSettingService.getUserEmailConnectors(request.getLocale(), request.getRemoteUser());
  }
}
