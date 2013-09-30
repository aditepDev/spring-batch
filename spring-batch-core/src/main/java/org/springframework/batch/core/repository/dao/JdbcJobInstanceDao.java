/*
 * Copyright 2006-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package org.springframework.batch.core.repository.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.core.DefaultJobKeyGenerator;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobKeyGenerator;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.ParameterizedRowMapper;
import org.springframework.jdbc.support.incrementer.DataFieldMaxValueIncrementer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * JDBC implementation of {@link JobInstanceDao}. Uses sequences (via Spring's
 * {@link DataFieldMaxValueIncrementer} abstraction) to create all primary keys
 * before inserting a new row. Objects are checked to ensure all mandatory
 * fields to be stored are not null. If any are found to be null, an
 * IllegalArgumentException will be thrown. This could be left to JdbcTemplate,
 * however, the exception will be fairly vague, and fails to highlight which
 * field caused the exception.
 *
 * @author Lucas Ward
 * @author Dave Syer
 * @author Robert Kasanicky
 * @author Michael Minella
 */
public class JdbcJobInstanceDao extends AbstractJdbcBatchMetadataDao implements
JobInstanceDao, InitializingBean {

	private static final String CREATE_JOB_INSTANCE = "INSERT into %PREFIX%JOB_INSTANCE(JOB_INSTANCE_ID, JOB_NAME, JOB_KEY, VERSION)"
			+ " values (?, ?, ?, ?)";

	private static final String FIND_JOBS_WITH_NAME = "SELECT JOB_INSTANCE_ID, JOB_NAME from %PREFIX%JOB_INSTANCE where JOB_NAME = ?";

	private static final String FIND_JOBS_WITH_KEY = FIND_JOBS_WITH_NAME
			+ " and JOB_KEY = ?";

	private static final String COUNT_JOBS_WITH_NAME = "SELECT COUNT(*) from %PREFIX%JOB_INSTANCE where JOB_NAME = ?";

	private static final String FIND_JOBS_WITH_EMPTY_KEY = "SELECT JOB_INSTANCE_ID, JOB_NAME from %PREFIX%JOB_INSTANCE where JOB_NAME = ? and (JOB_KEY = ? OR JOB_KEY is NULL)";

	private static final String GET_JOB_FROM_ID = "SELECT JOB_INSTANCE_ID, JOB_NAME, JOB_KEY, VERSION from %PREFIX%JOB_INSTANCE where JOB_INSTANCE_ID = ?";

	private static final String GET_JOB_FROM_EXECUTION_ID = "SELECT ji.JOB_INSTANCE_ID, JOB_NAME, JOB_KEY, ji.VERSION from %PREFIX%JOB_INSTANCE ji, "
			+ "%PREFIX%JOB_EXECUTION je where JOB_EXECUTION_ID = ? and ji.JOB_INSTANCE_ID = je.JOB_INSTANCE_ID";

	private static final String FIND_JOB_NAMES = "SELECT distinct JOB_NAME from %PREFIX%JOB_INSTANCE order by JOB_NAME";

	private static final String FIND_LAST_JOBS_BY_NAME = "SELECT JOB_INSTANCE_ID, JOB_NAME from %PREFIX%JOB_INSTANCE where JOB_NAME = ? order by JOB_INSTANCE_ID desc";

	private DataFieldMaxValueIncrementer jobIncrementer;

	private JobKeyGenerator<JobParameters> jobKeyGenerator = new DefaultJobKeyGenerator();

	/**
	 * In this jdbc implementation a job id is obtained by asking the
	 * jobIncrementer (which is likely a sequence) for the next long value, and
	 * then passing the Id and parameter values into an INSERT statement.
	 *
	 * @see JobInstanceDao#createJobInstance(String, JobParameters)
	 * @throws IllegalArgumentException
	 *             if any {@link JobParameters} fields are null.
	 */
	@Override
	public JobInstance createJobInstance(String jobName,
			JobParameters jobParameters) {

		Assert.notNull(jobName, "Job name must not be null.");
		Assert.notNull(jobParameters, "JobParameters must not be null.");

		Assert.state(getJobInstance(jobName, jobParameters) == null,
				"JobInstance must not already exist");

		Long jobId = jobIncrementer.nextLongValue();

		JobInstance jobInstance = new JobInstance(jobId, jobName);
		jobInstance.incrementVersion();

		Object[] parameters = new Object[] { jobId, jobName,
				jobKeyGenerator.generateKey(jobParameters), jobInstance.getVersion() };
		getJdbcTemplate().update(
				getQuery(CREATE_JOB_INSTANCE),
				parameters,
				new int[] { Types.BIGINT, Types.VARCHAR, Types.VARCHAR,
					Types.INTEGER });

		return jobInstance;
	}

	/**
	 * The job table is queried for <strong>any</strong> jobs that match the
	 * given identifier, adding them to a list via the RowMapper callback.
	 *
	 * @see JobInstanceDao#getJobInstance(String, JobParameters)
	 * @throws IllegalArgumentException
	 *             if any {@link JobParameters} fields are null.
	 */
	@Override
	public JobInstance getJobInstance(final String jobName,
			final JobParameters jobParameters) {

		Assert.notNull(jobName, "Job name must not be null.");
		Assert.notNull(jobParameters, "JobParameters must not be null.");

		String jobKey = jobKeyGenerator.generateKey(jobParameters);

		ParameterizedRowMapper<JobInstance> rowMapper = new JobInstanceRowMapper();

		List<JobInstance> instances;
		if (StringUtils.hasLength(jobKey)) {
			instances = getJdbcTemplate().query(getQuery(FIND_JOBS_WITH_KEY),
					rowMapper, jobName, jobKey);
		} else {
			instances = getJdbcTemplate().query(
					getQuery(FIND_JOBS_WITH_EMPTY_KEY), rowMapper, jobName,
					jobKey);
		}

		if (instances.isEmpty()) {
			return null;
		} else {
			Assert.state(instances.size() == 1);
			return instances.get(0);
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.springframework.batch.core.repository.dao.JobInstanceDao#getJobInstance
	 * (java.lang.Long)
	 */
	@Override
	public JobInstance getJobInstance(Long instanceId) {

		try {
			return getJdbcTemplate().queryForObject(getQuery(GET_JOB_FROM_ID),
					new JobInstanceRowMapper(), instanceId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.springframework.batch.core.repository.dao.JobInstanceDao#getJobNames
	 * ()
	 */
	@Override
	public List<String> getJobNames() {
		return getJdbcTemplate().query(getQuery(FIND_JOB_NAMES),
				new ParameterizedRowMapper<String>() {
			@Override
			public String mapRow(ResultSet rs, int rowNum)
					throws SQLException {
				return rs.getString(1);
			}
		});
	}

	/*
	 * (non-Javadoc)
	 *
	 * @seeorg.springframework.batch.core.repository.dao.JobInstanceDao#
	 * getLastJobInstances(java.lang.String, int)
	 */
	@Override
	@SuppressWarnings("rawtypes")
	public List<JobInstance> getJobInstances(String jobName, final int start,
			final int count) {

		ResultSetExtractor extractor = new ResultSetExtractor() {

			private List<JobInstance> list = new ArrayList<JobInstance>();

			@Override
			public Object extractData(ResultSet rs) throws SQLException,
			DataAccessException {
				int rowNum = 0;
				while (rowNum < start && rs.next()) {
					rowNum++;
				}
				while (rowNum < start + count && rs.next()) {
					ParameterizedRowMapper<JobInstance> rowMapper = new JobInstanceRowMapper();
					list.add(rowMapper.mapRow(rs, rowNum));
					rowNum++;
				}
				return list;
			}

		};

		@SuppressWarnings("unchecked")
		List<JobInstance> result = (List<JobInstance>) getJdbcTemplate().query(getQuery(FIND_LAST_JOBS_BY_NAME),
				new Object[] { jobName }, extractor);

		return result;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.springframework.batch.core.repository.dao.JobInstanceDao#getJobInstance
	 * (org.springframework.batch.core.JobExecution)
	 */
	@Override
	public JobInstance getJobInstance(JobExecution jobExecution) {

		try {
			return getJdbcTemplate().queryForObject(
					getQuery(GET_JOB_FROM_EXECUTION_ID),
					new JobInstanceRowMapper(), jobExecution.getId());
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	/* (non-Javadoc)
	 * @see org.springframework.batch.core.repository.dao.JobInstanceDao#getJobInstanceCount(java.lang.String)
	 */
	@Override
	public int getJobInstanceCount(String jobName) throws NoSuchJobException {

		try {
			return getJdbcTemplate().queryForInt(
					getQuery(COUNT_JOBS_WITH_NAME),
					jobName);
		} catch (EmptyResultDataAccessException e) {
			throw new NoSuchJobException("No job instances were found for job name " + jobName);
		}
	}

	/**
	 * Setter for {@link DataFieldMaxValueIncrementer} to be used when
	 * generating primary keys for {@link JobInstance} instances.
	 *
	 * @param jobIncrementer
	 *            the {@link DataFieldMaxValueIncrementer}
	 */
	public void setJobIncrementer(DataFieldMaxValueIncrementer jobIncrementer) {
		this.jobIncrementer = jobIncrementer;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		super.afterPropertiesSet();
		Assert.notNull(jobIncrementer);
	}

	/**
	 * @author Dave Syer
	 *
	 */
	private final class JobInstanceRowMapper implements
	ParameterizedRowMapper<JobInstance> {

		public JobInstanceRowMapper() {
		}

		@Override
		public JobInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
			JobInstance jobInstance = new JobInstance(rs.getLong(1), rs.getString(2));
			// should always be at version=0 because they never get updated
			jobInstance.incrementVersion();
			return jobInstance;
		}
	}
}