/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.cmd;

import static org.camunda.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.io.Serializable;
import java.util.Collections;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.impl.ProcessEngineLogger;
import org.camunda.bpm.engine.impl.cfg.CommandChecker;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.interceptor.CommandExecutor;
import org.camunda.bpm.engine.impl.jobexecutor.FailedJobListener;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutorContext;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutorLogger;
import org.camunda.bpm.engine.impl.jobexecutor.JobFailureCollector;
import org.camunda.bpm.engine.impl.jobexecutor.SuccessfulJobListener;
import org.camunda.bpm.engine.impl.persistence.entity.JobEntity;

/**
 * @author Tom Baeyens
 * @author Daniel Meyer
 */
public class ExecuteJobsCmd implements Command<Object>, Serializable {

  private static final long serialVersionUID = 1L;

  private final static JobExecutorLogger LOG = ProcessEngineLogger.JOB_EXECUTOR_LOGGER;

  protected String jobId;

  public ExecuteJobsCmd(String jobId) {
    this.jobId = jobId;
  }

  public Object execute(CommandContext commandContext) {
    ensureNotNull("jobId", jobId);

    final JobEntity job = commandContext.getDbEntityManager().selectById(JobEntity.class, jobId);

    final ProcessEngineConfigurationImpl processEngineConfiguration = Context.getProcessEngineConfiguration();
    final CommandExecutor commandExecutor = processEngineConfiguration.getCommandExecutorTxRequiresNew();
    final IdentityService identityService = processEngineConfiguration.getIdentityService();

    final JobExecutorContext jobExecutorContext = Context.getJobExecutorContext();
    final JobFailureCollector jobFailureCollector = new JobFailureCollector();

    if (job == null) {
      if (jobExecutorContext != null) {
        // CAM-1842
        // Job was acquired but does not exist anymore. This is not a problem.
        // It usually means that the job has been deleted after it was acquired which can happen if the
        // the activity instance corresponding to the job is cancelled.
        LOG.debugAcquiredJobNotFound(jobId);
        return null;

      } else {
        throw LOG.jobNotFoundException(jobId);
      }
    }

    if (jobExecutorContext == null) { // if null, then we are not called by the job executor
      for(CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
        checker.checkUpdateJob(job);
      }
    } else {
      jobExecutorContext.setCurrentJob(job);

      // if the job is called by the job executor then set the tenant id of the job
      // as authenticated tenant to enable tenant checks
      String tenantId = job.getTenantId();
      if (tenantId != null) {
        identityService.setAuthentication(null, null, Collections.singletonList(tenantId));
      }
    }

    try {
      executeJob(commandExecutor, job, jobFailureCollector);

    } catch (RuntimeException exception) {
      handleJobFailure(job, jobFailureCollector, exception);
      // throw the original exception to indicate the ExecuteJobCmd failed
      throw exception;

    } catch (Throwable exception) {
     handleJobFailure(job, jobFailureCollector, exception);
     // wrap the exception and throw it to indicate the ExecuteJobCmd failed
     throw LOG.wrapJobExecutionFailure(job, exception);

    } finally {
      invokeJobListener(commandExecutor, jobFailureCollector);

      if (jobExecutorContext != null) {
        jobExecutorContext.setCurrentJob(null);

        identityService.clearAuthentication();
      }
    }

    return null;
  }

  protected void executeJob(CommandExecutor commandExecutor, final JobEntity job, final JobFailureCollector jobFailureCollector) {
    // execute the job within a new transaction
    commandExecutor.execute(new Command<Void>() {

      public Void execute(CommandContext commandContext) {
        // register as command context close lister to intercept exceptions on flush
        commandContext.registerCommandContextListener(jobFailureCollector);

        commandContext.setCurrentJob(job);

        job.execute(commandContext);
        return null;
      }
    });
  }

  protected void handleJobFailure(final JobEntity job, final JobFailureCollector jobFailureCollector, Throwable exception) {
    LOG.exceptionWhileExecutingJob(job, exception);

    jobFailureCollector.setFailure(exception);
  }

  protected void invokeJobListener(CommandExecutor commandExecutor, JobFailureCollector jobFailureCollector) {
    if (jobFailureCollector.getFailure() != null) {
      // the failed job listener is responsible for decrementing the retries and logging the exception to the DB.
      FailedJobListener failedJobListener = createFailedJobListener(commandExecutor, jobFailureCollector.getFailure());
      commandExecutor.execute(failedJobListener);
    } else {
      SuccessfulJobListener successListener = createSuccessfulJobListener(commandExecutor);
      commandExecutor.execute(successListener);
    }
  }

  protected FailedJobListener createFailedJobListener(CommandExecutor commandExecutor, Throwable exception) {
    return new FailedJobListener(commandExecutor, jobId, exception);
  }

  protected SuccessfulJobListener createSuccessfulJobListener(CommandExecutor commandExecutor) {
    return new SuccessfulJobListener();
  }

}
