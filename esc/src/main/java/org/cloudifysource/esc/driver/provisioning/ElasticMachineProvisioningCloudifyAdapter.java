/*******************************************************************************
 * Copyright (c) 2011 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.cloudifysource.esc.driver.provisioning;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.jini.core.discovery.LookupLocator;

import org.cloudifysource.dsl.cloud.Cloud;
import org.cloudifysource.dsl.cloud.CloudTemplate;
import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.dsl.internal.DSLException;
import org.cloudifysource.dsl.internal.ServiceReader;
import org.cloudifysource.dsl.utils.ServiceUtils;
import org.cloudifysource.esc.driver.provisioning.context.DefaultProvisioningDriverClassContext;
import org.cloudifysource.esc.driver.provisioning.context.ProvisioningDriverClassContext;
import org.cloudifysource.esc.driver.provisioning.context.ProvisioningDriverClassContextAware;
import org.cloudifysource.esc.installer.AgentlessInstaller;
import org.cloudifysource.esc.installer.InstallationDetails;
import org.cloudifysource.esc.installer.InstallerException;
import org.cloudifysource.esc.util.Utils;
import org.openspaces.admin.Admin;
import org.openspaces.admin.AdminException;
import org.openspaces.admin.AdminFactory;
import org.openspaces.admin.bean.BeanConfigurationException;
import org.openspaces.admin.gsa.GridServiceAgent;
import org.openspaces.admin.pu.elastic.ElasticMachineProvisioningConfig;
import org.openspaces.core.bean.Bean;
import org.openspaces.grid.gsm.capacity.CapacityRequirements;
import org.openspaces.grid.gsm.capacity.CpuCapacityRequirement;
import org.openspaces.grid.gsm.capacity.MemoryCapacityRequirement;
import org.openspaces.grid.gsm.machines.isolation.ElasticProcessingUnitMachineIsolation;
import org.openspaces.grid.gsm.machines.plugins.ElasticMachineProvisioning;
import org.openspaces.grid.gsm.machines.plugins.ElasticMachineProvisioningException;

/****************************
 * An ESM machine provisioning implementation used by the Cloudify cloud driver. All calls to start/stop a machine are
 * delegated to the CloudifyProvisioning implementation. If the started machine does not have an agent running, this
 * class will install gigaspaces and start the agent using the Agent-less Installer process.
 * 
 * @author barakme
 * 
 */
public class ElasticMachineProvisioningCloudifyAdapter implements ElasticMachineProvisioning, Bean {

	private static final int MILLISECONDS_IN_SECOND = 1000;

	private static final int DEFAULT_SHUTDOWN_TIMEOUT_AFTER_PROVISION_FAILURE = 5;

	// TODO: Store this object inside ElasticMachineProvisioningContext instead of a static variable
	private static final Map<String, ProvisioningDriverClassContext> PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME =
			new HashMap<String, ProvisioningDriverClassContext>();

	private static Admin globalAdminInstance = null;
	private static final Object GLOBAL_ADMIN_MUTEX = new Object();

	private static Admin getGlobalAdminInstance(final Admin esmAdminInstance) {
		synchronized (GLOBAL_ADMIN_MUTEX) {
			if (globalAdminInstance == null) {
				// create admin clone from esm instance
				AdminFactory factory = new AdminFactory();
				for (String group : esmAdminInstance.getGroups()) {
					factory.addGroup(group);
				}
				for (LookupLocator locator : esmAdminInstance.getLocators()) {
					factory.addLocator(locator.getHost() + ":" + locator.getPort());
				}
				globalAdminInstance = factory.createAdmin();
			}

			return globalAdminInstance;
		}
	}

	private ProvisioningDriver cloudifyProvisioning;
	private Admin originalESMAdmin;
	private Map<String, String> properties;
	private Cloud cloud;
	private String cloudTemplateName;
	private String lookupLocatorsString;
	private CloudifyMachineProvisioningConfig config;
	private java.util.logging.Logger logger;

	@Override
	public boolean isStartMachineSupported() {
		return true;
	}

	@Override
	public GridServiceAgent[] getDiscoveredMachines(final long duration, final TimeUnit unit)
			throws ElasticMachineProvisioningException, InterruptedException, TimeoutException {
		// TODO - query the cloud and cross reference with the originalESMAdmin
		return this.originalESMAdmin.getGridServiceAgents().getAgents();
	}

	private InstallationDetails createInstallationDetails(final Cloud cloud, final MachineDetails md)
			throws FileNotFoundException {
		final CloudTemplate template = this.cloud.getTemplates().get(this.cloudTemplateName);
		final String[] zones = this.config.getGridServiceAgentZones();
		final InstallationDetails details =
				Utils.createInstallationDetails(md, cloud, template, zones, lookupLocatorsString,
						this.originalESMAdmin, false,
						null);

		logger.info("Created new Installation Details: " + details);
		return details;

	}

	@Override
	public GridServiceAgent startMachine(final long duration, final TimeUnit unit)
			throws ElasticMachineProvisioningException, InterruptedException, TimeoutException {

		logger.info("Cloudify Adapter is starting a new machine");
		// calculate timeout
		final long end = System.currentTimeMillis() + unit.toMillis(duration);

		// provision the machine
		logger.info("Calling provisioning implementation for new machine");
		MachineDetails machineDetails = null;
		try {
			cloudifyProvisioning.setAdmin(getGlobalAdminInstance(originalESMAdmin));
			machineDetails = provisionMachine(duration, unit);
		} catch (final Exception e) {
			logger.log(Level.WARNING, "Failed to provision machine, reason: " + e.getMessage(), e);
			throw new ElasticMachineProvisioningException("Failed to provisiong machine: " + e.getMessage(), e);
		}

		logger.info("Machine was provisioned by implementation. Machine is: " + machineDetails);

		// which IP should be used in the cluster
		String machineIp = null;
		if (cloud.getConfiguration().isConnectToPrivateIp()) {
			machineIp = machineDetails.getPrivateAddress();
		} else {
			machineIp = machineDetails.getPublicAddress();
		}
		try {
			// check for timeout
			checkForProvisioningTimeout(end, machineDetails);

			if (machineDetails.isAgentRunning()) {
				logger.info("Machine provisioning provided a machine and indicated that an agent is already running");
			} else {
				// install gigaspaces and start agent
				logger.info("Cloudify Adapter is installing Cloudify on new machine");
				installAndStartAgent(machineDetails, end);
				// check for timeout again - the installation step can also take a
				// while to complete.
				checkForProvisioningTimeout(end, machineDetails);

			}

			// There is another case here, that we do not handle - where the started image
			// has the cloudify distro, but the agent is not running. This means we should
			// run a modified remote execution script. Not sure if we really need this, though,
			// as this scenario really does not offer a better experience. If required, handling will
			// be added here.

			if (machineIp == null) {
				throw new IllegalArgumentException("The IP of the new machine is null! Machine Details are: "
						+ machineDetails + " .");
			}
			// wait for GSA to become available
			logger.info("Cloudify adapter is waiting for GSA to become available");
			final GridServiceAgent gsa = waitForGsa(machineIp, end);
			if (gsa == null) {
				// GSA did not start correctly or on time - shutdown the machine
				// handleGSANotFound(machineIp);
				throw new TimeoutException("New machine was provisioned and Cloudify was installed, "
						+ "but a GSA was not discovered on the new machine: " + machineDetails);
			}
			return gsa;
		} catch (final ElasticMachineProvisioningException e) {
			logger.info("ElasticMachineProvisioningException occurred, " + e.getMessage());
			logger.info(Arrays.toString(e.getStackTrace()));
			handleExceptionAfterMachineCreated(machineIp, machineDetails, end);
			throw e;
		} catch (final TimeoutException e) {
			logger.info("TimeoutException occurred, " + e.getMessage());
			logger.info(Arrays.toString(e.getStackTrace()));
			handleExceptionAfterMachineCreated(machineIp, machineDetails, end);
			throw e;
		} catch (final InterruptedException e) {
			logger.info("InterruptedException occurred, " + e.getMessage());
			logger.info(Arrays.toString(e.getStackTrace()));
			handleExceptionAfterMachineCreated(machineIp, machineDetails, end);
			throw e;
		}

	}

	private void handleExceptionAfterMachineCreated(final String machineIp, final MachineDetails machineDetails,
			final long end) {
		try {
			// if an agent is found (not supposed to, we got here after it wasn't found earlier) - shut it down
			if (machineIp != null && !machineIp.trim().isEmpty()) {
				try {
					final GridServiceAgent agent = getGSAByIpOrHost(machineIp);
					if (agent != null) {
						logger.info("handleExceptionAfterMachineCreated is shutting down agent: " + agent
								+ " on host: " + machineIp);

						agent.shutdown();
						logger.fine("Agent on host: " + machineIp + " successfully shut down");

					}
				} catch (final Exception e) {
					// even if shutting down the agent failed, this node will be shut down later
					logger.log(Level.WARNING, "Failed to shutdown agent on host: " + machineIp
							+ ". Continuing with shutdown of " + "machine.", e);
				}
			}

			logger.info("Stopping machine " + machineDetails.getPrivateAddress()
					+ ", DEFAULT_SHUTDOWN_TIMEOUT_AFTER_PROVISION_FAILURE");
			this.cloudifyProvisioning.stopMachine(machineDetails.getPrivateAddress(),
					DEFAULT_SHUTDOWN_TIMEOUT_AFTER_PROVISION_FAILURE, TimeUnit.MINUTES);
		} catch (final Exception e) {
			logger.log(
					Level.WARNING,
					"Machine Provisioning failed. "
							+ "An error was encountered while trying to shutdown the new machine ( "
							+ machineDetails.toString() + "). Error was: " + e.getMessage(), e);
		}
	}

	private void checkForProvisioningTimeout(final long end, final MachineDetails machineDetails)
			throws TimeoutException, ElasticMachineProvisioningException, InterruptedException {
		if (System.currentTimeMillis() > end) {
			logger.warning("Provisioning of new machine exceeded the required timeout. Shutting down the new machine ("
					+ machineDetails.toString() + ")");
			// creating the new machine took too long! clean up and throw a
			// timeout
			throw new TimeoutException("New machine provisioning exceeded the required timeout");

		}
	}

	private void installAndStartAgent(final MachineDetails machineDetails, final long end)
			throws TimeoutException, InterruptedException, ElasticMachineProvisioningException {
		final AgentlessInstaller installer = new AgentlessInstaller();

		InstallationDetails installationDetails;
		try {
			installationDetails = createInstallationDetails(cloud, machineDetails);
		} catch (final FileNotFoundException e) {
			throw new ElasticMachineProvisioningException("Failed to create installation details for agent: "
					+ e.getMessage(), e);
		}

		logger.info("Starting agentless installation process on started machine with installation details: "
				+ installationDetails);
		// Update the logging level of jsch used by the AgentlessInstaller
		Logger.getLogger(AgentlessInstaller.SSH_LOGGER_NAME).setLevel(
				Level.parse(cloud.getProvider().getSshLoggingLevel()));

		// Execute agentless installation on the remote machine
		try {
			installer.installOnMachineWithIP(installationDetails, remainingTimeTill(end), TimeUnit.MILLISECONDS);
		} catch (final InstallerException e) {
			throw new ElasticMachineProvisioningException(
					"Failed to install Cloudify Agent on newly provisioned machine: " + e.getMessage(), e);
		}
	}

	private long remainingTimeTill(final long end)
			throws TimeoutException {
		final long remaining = end - System.currentTimeMillis();
		if (remaining <= 0) {
			throw new TimeoutException("Passed target end time " + new Date(end));
		}
		return remaining;
	}

	private MachineDetails provisionMachine(final long duration, final TimeUnit unit)
			throws TimeoutException, ElasticMachineProvisioningException {
		MachineDetails machineDetails;
		try {
			machineDetails = cloudifyProvisioning.startMachine(duration, unit);
		} catch (final CloudProvisioningException e) {
			throw new ElasticMachineProvisioningException("Failed to start machine: " + e.getMessage(), e);
		}

		if (machineDetails == null) {
			throw new IllegalStateException("Provisioning provider: " + cloudifyProvisioning.getClass().getName()
					+ " returned null when calling startMachine");
		}

		logger.info("New machine was provisioned. Machine details: " + machineDetails);
		return machineDetails;
	}

	private GridServiceAgent waitForGsa(final String machineIp, final long end)
			throws InterruptedException, TimeoutException {

		while (Utils.millisUntil(end) > 0) {
			final GridServiceAgent gsa = getGSAByIpOrHost(machineIp);
			if (gsa != null) {
				return gsa;
			}

			Thread.sleep(MILLISECONDS_IN_SECOND);

		}
		return null;

	}

	private GridServiceAgent getGSAByIpOrHost(final String machineIp) {
		GridServiceAgent gsa = originalESMAdmin.getGridServiceAgents().getHostAddress().get(machineIp);
		if (gsa != null) {
			return gsa;
		}

		gsa = originalESMAdmin.getGridServiceAgents().getHostNames().get(machineIp);
		if (gsa != null) {
			return gsa;
		}
		return null;
	}

	@Override
	public CapacityRequirements getCapacityOfSingleMachine() {
		final CloudTemplate template = cloud.getTemplates().get(this.cloudTemplateName);
		final CapacityRequirements capacityRequirements =
				new CapacityRequirements(new MemoryCapacityRequirement((long) template.getMachineMemoryMB()),
						new CpuCapacityRequirement(template.getNumberOfCores()));
		logger.info("Capacity requirements for a single machine are: " + capacityRequirements);
		return capacityRequirements;

	}

	@Override
	public boolean stopMachine(final GridServiceAgent agent, final long duration, final TimeUnit unit)
			throws ElasticMachineProvisioningException, InterruptedException, TimeoutException {

		final String machineIp = agent.getMachine().getHostAddress();
		logger.fine("Shutting down agent: " + agent + " on host: " + machineIp);
		try {
			agent.shutdown();
			logger.fine("Agent on host: " + machineIp + " successfully shut down");
		} catch (final Exception e) {
			logger.log(Level.WARNING, "Failed to shutdown agent on host: " + machineIp
					+ ". Continuing with shutdown of machine.", e);
		}

		try {
			logger.fine("Cloudify Adapter is shutting down machine with ip: " + machineIp);

			final boolean shutdownResult = this.cloudifyProvisioning.stopMachine(machineIp, duration, unit);
			logger.fine("Shutdown result of machine: " + machineIp + " was: " + shutdownResult);

			return shutdownResult;

		} catch (final CloudProvisioningException e) {
			throw new ElasticMachineProvisioningException("Attempt to shutdown machine with IP: " + machineIp
					+ " for agent with UID: " + agent.getUid() + " has failed with error: " + e.getMessage(), e);
		} catch (final AdminException e) {
			throw new ElasticMachineProvisioningException("Failed to shutdown agent "
					+ agent.getMachine().getHostAddress(), e);
		}
	}

	@Override
	public ElasticMachineProvisioningConfig getConfig() {
		return this.config;
	}

	// //////////////////////////////////
	// OpenSpaces Bean Implementation //
	// //////////////////////////////////
	@Override
	public void setAdmin(final Admin admin) {
		this.originalESMAdmin = admin;

	}

	@Override
	public void setProperties(final Map<String, String> properties) {
		this.properties = properties;
		this.config = new CloudifyMachineProvisioningConfig(properties);

	}

	@Override
	public Map<String, String> getProperties() {
		return this.properties;
	}

	@Override
	public void afterPropertiesSet()
			throws Exception {

		final String[] zones = this.config.getGridServiceAgentZones();
		logger = java.util.logging.Logger
				.getLogger(ElasticMachineProvisioningCloudifyAdapter.class.getName() + "-" + Arrays.toString(zones));

		final String cloudContents = properties.get(CloudifyConstants.ELASTIC_PROPERTIES_CLOUD_CONFIGURATION);
		if (cloudContents == null) {
			throw new IllegalArgumentException("Cloud configuration was not set!");
		}

		try {
			this.cloud = ServiceReader.readCloud(cloudContents);
			this.cloudTemplateName = properties.get(CloudifyConstants.ELASTIC_PROPERTIES_CLOUD_TEMPLATE_NAME);

			if (this.cloudTemplateName == null) {
				throw new BeanConfigurationException("Cloud template was not set!");
			}

			final CloudTemplate cloudTemplate = this.cloud.getTemplates().get(this.cloudTemplateName);
			if (cloudTemplate == null) {
				throw new BeanConfigurationException("The provided cloud template name: " + this.cloudTemplateName
						+ " was not found in the cloud configuration");
			}

			// This code runs on the ESM in the remote machine,
			// so set the local directory to the value of the remote directory
			logger.info("Remote Directory is: " + cloudTemplate.getRemoteDirectory());
			if (ServiceUtils.isWindows()) {
				logger.info("Windows machine - modifying local directory location");
				String localDirectoryName = cloudTemplate.getRemoteDirectory();
				localDirectoryName = localDirectoryName.replace("$", "");
				if (localDirectoryName.startsWith("/")) {
					localDirectoryName = localDirectoryName.substring(1);
				}
				if (localDirectoryName.charAt(1) == '/') {
					localDirectoryName = localDirectoryName.substring(0, 1) + ":" + localDirectoryName.substring(1);
				}
				logger.info("Modified local dir name is: " + localDirectoryName);

				cloudTemplate.setLocalDirectory(localDirectoryName);
			} else {
				cloudTemplate.setLocalDirectory(cloudTemplate.getRemoteDirectory());
			}

			// load the provisioning class and set it up
			try {
				this.cloudifyProvisioning =
						(ProvisioningDriver) Class.forName(this.cloud.getConfiguration().getClassName()).newInstance();

				if (cloudifyProvisioning instanceof ProvisioningDriverClassContextAware) {
					final ProvisioningDriverClassContext provisioningDriverContext =
							lazyCreateProvisioningDriverClassContext(cloudifyProvisioning);
					final ProvisioningDriverClassContextAware contextAware =
							(ProvisioningDriverClassContextAware) cloudifyProvisioning;
					contextAware.setProvisioningDriverClassContext(provisioningDriverContext);
				}

				this.cloudifyProvisioning.setConfig(cloud, cloudTemplateName, false);

			} catch (final ClassNotFoundException e) {
				throw new BeanConfigurationException("Failed to load provisioning class for cloud: "
						+ this.cloud.getName() + ". Class not found: " + this.cloud.getConfiguration().getClassName(),
						e);
			} catch (final Exception e) {
				throw new BeanConfigurationException("Failed to load provisioning class for cloud: "
						+ this.cloud.getName(), e);
			}

			this.lookupLocatorsString = createLocatorsString();

			logger.info("Locators string used for new instances will be: " + this.lookupLocatorsString);

		} catch (final DSLException e) {
			logger.severe("Could not parse the provided cloud configuration: " + cloudContents + ": " + e.getMessage());
			throw new BeanConfigurationException("Could not parse the prvided cloud configuration: " + cloudContents
					+ ": " + e.getMessage());
		}

	}

	private static ProvisioningDriverClassContext lazyCreateProvisioningDriverClassContext(
			final ProvisioningDriver cloudifyProvisioning) {

		final String cloudDriverUniqueId = cloudifyProvisioning.getClass().getName();
		synchronized (PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME) {
			if (!PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME.containsKey(cloudDriverUniqueId)) {
				PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME.put(cloudDriverUniqueId,
						new DefaultProvisioningDriverClassContext());
			}
		}
		final ProvisioningDriverClassContext provisioningDriverContext =
				PROVISIONING_DRIVER_CONTEXT_PER_DRIVER_CLASSNAME.get(cloudDriverUniqueId);
		return provisioningDriverContext;
	}

	private String createLocatorsString() {
		final LookupLocator[] locators = this.originalESMAdmin.getLocators();
		final StringBuilder sb = new StringBuilder();
		for (final LookupLocator lookupLocator : locators) {
			sb.append(lookupLocator.getHost()).append(':').append(lookupLocator.getPort()).append(',');
		}
		if (!sb.toString().isEmpty()) {
			sb.setLength(sb.length() - 1);
		}
		return sb.toString();
	}

	@Override
	public void destroy()
			throws Exception {
		this.cloudifyProvisioning.close();
		// not closing globalAdminMutex, it's a static object, and this is intentional.
	}

	@Override
	public void setElasticProcessingUnitMachineIsolation(final ElasticProcessingUnitMachineIsolation isolation) {
		
	}

}
