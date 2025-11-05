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
package org.exoplatform.emailConnector.job;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

@DisallowConcurrentExecution
public class EmailBoxSyncJob implements Job {
  private static final Log LOG = ExoLogger.getLogger(EmailBoxSyncJob.class);

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    String emailBoxSyncJobName = context.getJobDetail().getKey().getName();
    String username = emailBoxSyncJobName.substring(0, emailBoxSyncJobName.indexOf(EmailConnectorUtils.EMAIL_BOX_SYNC_JOB_NAME));
    EmailBoxService emailBoxService = CommonsUtils.getService(EmailBoxService.class);
    LOG.debug("Start email box sync job for user: {}", username);
    try {
      emailBoxService.synchronize(username);
    } catch (IllegalAccessException e) {
      LOG.warn("Synchronization is not allowed for user {} ", username);
    }
    LOG.debug("End email box sync job for user: {}", username);
  }
}
