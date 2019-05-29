/**
 *****************************************************************************
 Copyright (c) 2015-19 IBM Corporation and other Contributors.
 All rights reserved. This program and the accompanying materials
 are made available under the terms of the Eclipse Public License v1.0
 which accompanies this distribution, and is available at
 http://www.eclipse.org/legal/epl-v10.html
 *****************************************************************************
 *
 */
package com.ibm.wiotp.sdk.device;

import java.io.UnsupportedEncodingException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.ibm.wiotp.sdk.device.config.DeviceConfig;
import com.ibm.wiotp.sdk.devicemgmt.CustomActionHandler;
import com.ibm.wiotp.sdk.devicemgmt.DeviceActionHandler;
import com.ibm.wiotp.sdk.devicemgmt.DeviceData;
import com.ibm.wiotp.sdk.devicemgmt.DeviceFirmwareHandler;
import com.ibm.wiotp.sdk.devicemgmt.LogSeverity;
import com.ibm.wiotp.sdk.devicemgmt.internal.DMAgentTopic;
import com.ibm.wiotp.sdk.devicemgmt.internal.DMServerTopic;
import com.ibm.wiotp.sdk.devicemgmt.internal.ManagedClient;
import com.ibm.wiotp.sdk.devicemgmt.internal.ResponseCode;
import com.ibm.wiotp.sdk.devicemgmt.internal.device.DeviceDMAgentTopic;
import com.ibm.wiotp.sdk.devicemgmt.internal.device.DeviceDMServerTopic;
import com.ibm.wiotp.sdk.devicemgmt.internal.handler.DMRequestHandler;

/**
 * A managed device class, used by device, that connects the device as managed device to IBM Watson IoT Platform and
 * enables devices to perform one or more Device Management operations,
 *
 * <p>The device management feature enhances the Watson IoT Platform service with new capabilities
 * for managing devices.</p>
 *
 * <p> What does Device Management add? </p>
 * <ul class="simple">
 * <li>Control and management of device lifecycles for both individual and batches of devices.</li>
 * <li>Device metadata and status information, enabling the creation of device dashboards and other tools.</li>
 * <li>Diagnostic information, both for connectivity to the Watson IoT Platform service, and device diagnostics.</li>
 * <li>Device management commands, like firmware update, and device reboot.</li>
 * </ul>
 * <p> This is a derived class from DeviceClient and can be used by embedded devices to perform both <b>Device operations
 * and Device Management operations</b>, i.e, the devices can use this class to do the following, </p>
 *
 * <ul class="simple">
 * <li>Publish device events</li>
 * <li>Subscribe to commands from application</li>
 * <li>Perform Device management operations like, manage, unmanage, firmware update, reboot,
 *    update location, Diagnostics informations, Factory Reset and etc..</li>
 * </ul>
 *
 */

public class ManagedDevice extends DeviceClient implements IMqttMessageListener, Runnable {

	private static final Logger LOG = LoggerFactory.getLogger(ManagedDevice.class);
	private static final int REGISTER_TIMEOUT_VALUE = 60 * 1000 * 2; // wait for 2 minute

	private final SynchronousQueue<JsonObject> queue = new SynchronousQueue<JsonObject>();

	private volatile boolean running = false;
	private BlockingQueue<JsonObject> publishQueue;
	JsonObject dummy = new JsonObject();

	private DeviceFirmwareHandler fwHandler = null;
	private DeviceActionHandler actionHandler = null;
	private CustomActionHandler customActionHandler = null;

	//Map to handle duplicate responses
	private Map<String, MqttMessage> requests = new HashMap<String, MqttMessage>();

	//Device specific information
	private DeviceData deviceData = null;

	@SuppressWarnings("unused")
	private boolean supportDeviceActions = false;
	@SuppressWarnings("unused")
	private boolean supportFirmwareActions = false;
	@SuppressWarnings("unused")
	private List<String> bundleIds;
	private boolean bManaged = false;
	@SuppressWarnings("unused")
	private Date dormantTime;
	private String responseSubscription = null;
	private ManagedDeviceClient client;

    /**
     * Constructor that creates a ManagedDevice object, but does not connect to
     * IBM Watson IoT Platform connect yet
     *
     * @param config      Configuration for the device
     * @param deviceData   The Device Model
     * @throws Exception   If the essential parameters are not set
     */
	public ManagedDevice(DeviceConfig config, DeviceData deviceData) throws Exception {
		super(config);
		if(deviceData == null) {
			LOG.warn("Could not create Managed Client without DeviceInformation !");
			throw new Exception("Could not create Managed Client without DeviceInformation !");
		}

		deviceData.setTypeId(config.identity.typeId);
		deviceData.setDeviceId(config.identity.deviceId);
		this.deviceData = deviceData;
		this.client = new ManagedDeviceClient(this);
	}

	public DeviceData getDeviceData() {
		return deviceData;
	}

	/**
	 * <p>This method just connects to the IBM Watson IoT Platform,
	 * Device needs to make a call to manage() to participate in Device
	 * Management activities.</p>
	 *
	 * <p>This method does nothing if the device is already connected. Also, this 
	 * method does not retry when the following exceptions occur.</p>
	 * 
	 * <ul class="simple">
	 *  <li> MqttSecurityException - One or more credentials are wrong
	 * 	<li>UnKnownHostException - Host doesn't exist. For example, a wrong organization name is used to connect.
	 * </ul>
	 * 
	 * @throws MqttException see above
	 * @throws NoSuchAlgorithmException TLS issues
	 * @throws KeyManagementException TLS issues
	 *
	 *
	 */
	@Override
    public void connect() throws MqttException, KeyManagementException, NoSuchAlgorithmException {
		if (this.isConnected()) {
			LOG.warn("Device is already connected");
			return;
		}
		super.connect();
	}


	/**
	 * <p>Send a device manage request to Watson IoT Platform</p>
	 *
	 * <p>A Device uses this request to become a managed device.
	 * It should be the first device management request sent by the
	 * Device after connecting to the IBM Watson IoT Platform.
	 * It would be usual for a device management agent to send this
	 * whenever is starts or restarts.</p>
	 *
	 * <p>This method connects the device to Watson IoT Platform connect if its not connected already</p>
	 *
	 * @param lifetime The length of time in seconds within
	 *        which the device must send another Manage device request.
	 *        if set to 0, the managed device will not become dormant.
	 *        When set, the minimum supported setting is 3600 (1 hour).
	 *
	 * @param supportFirmwareActions Tells whether the device supports firmware actions or not.
	 *        The device must add a firmware handler to handle the firmware requests.
	 *
	 * @param supportDeviceActions Tells whether the device supports Device actions or not.
	 *        The device must add a Device action handler to handle the reboot and factory reset requests.
	 *        
	 * @param bundleIds List of Device Management Extension bundleIds
	 *
	 * @return boolean response containing the status of the manage request
	 * @throws MqttException When there is a failure
	 */
	public boolean sendManageRequest(long lifetime, boolean supportFirmwareActions,
			boolean supportDeviceActions, List<String> bundleIds) throws MqttException {

		LOG.debug("lifetime(" + lifetime + "), supportFirmwareActions("+ supportFirmwareActions + "), supportDeviceActions("+supportDeviceActions+")");

		boolean success = false;
		String topic = client.getDMAgentTopic().getManageTopic();

		if (!this.isConnected()) {
			throw new RuntimeException("You must first connect the device before calling manage()");
		}

		this.supportDeviceActions = supportDeviceActions;
		this.supportFirmwareActions = supportFirmwareActions;
		this.bundleIds = bundleIds;
		JsonObject jsonPayload = new JsonObject();
		JsonObject supports = new JsonObject();
		supports.add("deviceActions", new JsonPrimitive(supportDeviceActions));
		supports.add("firmwareActions", new JsonPrimitive(supportFirmwareActions));
		if(bundleIds != null && bundleIds.size() > 0) {
			for(int i = 0; i < bundleIds.size(); i++) {
				supports.add(bundleIds.get(i), new JsonPrimitive(true));
			}
		}

		JsonObject data = new JsonObject();
		data.add("supports", supports);
		if (deviceData.getDeviceInfo() != null) {
			data.add("deviceInfo", deviceData.getDeviceInfo().toJsonObject());
		}
		if (deviceData.getMetadata() != null) {
			data.add("metadata", deviceData.getMetadata().getMetadata());
		}
		if(lifetime > 0) {
			data.add("lifetime", new JsonPrimitive(lifetime));
		}
		jsonPayload.add("d", data);


		JsonObject jsonResponse = sendAndWait(topic, jsonPayload, REGISTER_TIMEOUT_VALUE);
		if (jsonResponse != null && jsonResponse.get("rc").getAsInt() ==
				ResponseCode.DM_SUCCESS.getCode()) {
			DMRequestHandler.setRequestHandlers(this.client);
			if(!running) {
				publishQueue = new LinkedBlockingQueue<JsonObject>();
				Thread t = new Thread(this);
				t.start();
				running = true;
			}
			/*
			 * set the dormant time to a local variable, in case if the connection is
			 * lost due to n/w interruption, we need to send another manage request
			 * with the dormant time as the lifetime
			 */
			if(lifetime > 0) {
				Date currentTime = new Date();
				dormantTime = new Date(currentTime.getTime() + (lifetime * 1000));
			}
			success = true;
		}
		LOG.debug("Success (" + success + ")");

		bManaged = success;
		return success;
	}
	
	/**
	 * <p>Send a device manage request to Watson IoT Platform</p>
	 *
	 * <p>A Device uses this request to become a managed device.
	 * It should be the first device management request sent by the
	 * Device after connecting to the IBM Watson IoT Platform.
	 * It would be usual for a device management agent to send this
	 * whenever is starts or restarts.</p>
	 *
	 * <p>This method connects the device to Watson IoT Platform connect if its not connected already</p>
	 *
	 * @param lifetime The length of time in seconds within
	 *        which the device must send another Manage device request.
	 *        if set to 0, the managed device will not become dormant.
	 *        When set, the minimum supported setting is 3600 (1 hour).
	 *
	 * @param supportFirmwareActions Tells whether the device supports firmware actions or not.
	 *        The device must add a firmware handler to handle the firmware requests.
	 *
	 * @param supportDeviceActions Tells whether the device supports Device actions or not.
	 *        The device must add a Device action handler to handle the reboot and factory reset requests.
	 *        
	 * @param bundleId Unique identifier for a device management extension
	 *
	 * @return boolean response containing the status of the manage request
	 * @throws MqttException When there is a failure
	 */
	@SuppressWarnings("unchecked")
	public boolean sendManageRequest(long lifetime, boolean supportFirmwareActions,
			boolean supportDeviceActions, String bundleId) throws MqttException {

		@SuppressWarnings("rawtypes")
		List bundleList = new ArrayList();
		bundleList.add(bundleId);
		return sendManageRequest(lifetime, supportFirmwareActions, supportDeviceActions, bundleList);
	}
	
	/**
	 * <p>Send a device manage request to Watson IoT Platform</p>
	 *
	 * <p>A Device uses this request to become a managed device.
	 * It should be the first device management request sent by the
	 * Device after connecting to the IBM Watson IoT Platform.
	 * It would be usual for a device management agent to send this
	 * whenever is starts or restarts.</p>
	 *
	 * <p>This method connects the device to Watson IoT Platform connect if its not connected already</p>
	 *
	 * @param lifetime The length of time in seconds within
	 *        which the device must send another Manage device request.
	 *        if set to 0, the managed device will not become dormant.
	 *        When set, the minimum supported setting is 3600 (1 hour).
	 *
	 * @param supportFirmwareActions Tells whether the device supports firmware actions or not.
	 *        The device must add a firmware handler to handle the firmware requests.
	 *
	 * @param supportDeviceActions Tells whether the device supports Device actions or not.
	 *        The device must add a Device action handler to handle the reboot and factory reset requests.
	 *
	 * @return boolean response containing the status of the manage request
	 * @throws MqttException When there is a failure
	 */
	public boolean sendManageRequest(long lifetime, boolean supportFirmwareActions,
			boolean supportDeviceActions) throws MqttException {

		return sendManageRequest(lifetime, supportFirmwareActions, supportDeviceActions, (List<String>)null);
	}
	
	// DeviceDMAgentTopic

	/**
	 * Update the location.
	 *
	 * @param latitude	Latitude in decimal degrees using WGS84
	 * @param longitude Longitude in decimal degrees using WGS84
	 * @param elevation	Elevation in meters using WGS84
	 *
	 * @return code indicating whether the update is successful or not
	 *        (200 means success, otherwise unsuccessful)

	 */
	public int updateLocation(Double latitude, Double longitude, Double elevation) {
		return updateLocation(latitude, longitude, elevation, new Date());
	}

	/**
	 * Update the location of the device. This method converts the
	 * date in the required format. The caller just need to pass the date in java.util.Date format
	 *
	 * @param latitude	Latitude in decimal degrees using WGS84
	 * @param longitude Longitude in decimal degrees using WGS84
	 * @param elevation	Elevation in meters using WGS84
	 * @param measuredDateTime When the location information is retrieved
	 *
	 * @return code indicating whether the update is successful or not
	 *        (200 means success, otherwise unsuccessful)

	 */
	public int updateLocation(Double latitude, Double longitude, Double elevation, Date measuredDateTime) {
		return updateLocation(latitude, longitude, elevation, measuredDateTime, null);
	}

	/**
	 * Update the location of the device. This method converts the
	 * date in the required format. The caller just need to pass the date in java.util.Date format
	 *
	 * @param latitude	Latitude in decimal degrees using WGS84
	 * @param longitude Longitude in decimal degrees using WGS84
	 * @param elevation	Elevation in meters using WGS84
	 * @param measuredDateTime When the location information is retrieved
	 * @param accuracy	Accuracy of the position in meters
	 *
	 * @return code indicating whether the update is successful or not
	 *        (200 means success, otherwise unsuccessful)

	 */
	public int updateLocation(Double latitude, Double longitude, Double elevation, Date measuredDateTime, Double accuracy) {
		JsonObject jsonData = new JsonObject();

		JsonObject json = new JsonObject();
		json.addProperty("longitude", longitude);
		json.addProperty("latitude", latitude);
		if(elevation != null) {
			json.addProperty("elevation", elevation);
		}
		String utcTime = DateFormatUtils.formatUTC(measuredDateTime,
				DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.getPattern());
		json.addProperty("measuredDateTime", utcTime);

		if(accuracy != null) {
			json.addProperty("accuracy", accuracy);
		}

		jsonData.add("d", json);

		try {
			JsonObject response = sendAndWait(client.getDMAgentTopic().getUpdateLocationTopic(),
					jsonData, REGISTER_TIMEOUT_VALUE);
			if (response != null ) {
				return response.get("rc").getAsInt();
			}
		} catch (MqttException e) {
			LOG.warn(e.toString());
		}

		return 0;
	}

	/**
	 * Clear the Error Codes from IBM Watson IoT Platform for this device
	 * @return code indicating whether the clear operation is successful or not
	 *        (200 means success, otherwise unsuccessful)
	 */
	public int clearErrorCodes() {
		JsonObject jsonData = new JsonObject();
		try {
			JsonObject response = sendAndWait(client.getDMAgentTopic().getClearDiagErrorCodesTopic(),
					jsonData, REGISTER_TIMEOUT_VALUE);
			if (response != null ) {
				return response.get("rc").getAsInt();
			}
		} catch (MqttException e) {
			LOG.warn(e.toString());
		}
		return 0;
	}

	/**
	 * Clear the Logs from IBM Watson IoT Platform for this device
	 * @return code indicating whether the clear operation is successful or not
	 *        (200 means success, otherwise unsuccessful)
	 */
	public int clearLogs() {
		JsonObject jsonData = new JsonObject();
		try {
			JsonObject response = sendAndWait(client.getDMAgentTopic().getClearDiagLogsTopic(),
					jsonData, REGISTER_TIMEOUT_VALUE);
			if (response != null ) {
				return response.get("rc").getAsInt();
			}
		} catch (MqttException e) {
			LOG.warn(e.toString());
		}
		return 0;
	}

	/**
	 * Adds the current errorcode to IBM Watson IoT Platform.
	 *
	 * @param errorCode The "errorCode" is a current device error code that
	 * needs to be added to the Watson IoT Platform.
	 *
	 * @return code indicating whether the update is successful or not
	 *        (200 means success, otherwise unsuccessful)
	 */
	public int addErrorCode(int errorCode) {
		JsonObject jsonData = new JsonObject();
		JsonObject errorObj = new JsonObject();
		errorObj.addProperty("errorCode", errorCode);
		jsonData.add("d", errorObj);

		try {
			JsonObject response = sendAndWait(client.getDMAgentTopic().getAddErrorCodesTopic(),
					jsonData, REGISTER_TIMEOUT_VALUE);
			if (response != null ) {
				return response.get("rc").getAsInt();
			}
		} catch (MqttException e) {
			LOG.warn(e.toString());
		}
		return 0;
	}

	/**
	 * Appends a Log message to the Watson IoT Platform.
	 * @param message The Log message that needs to be added to the Watson IoT Platform.
	 * @param timestamp The Log timestamp
	 * @param severity the Log severity
	 *
	 * @return code indicating whether the update is successful or not
	 *        (200 means success, otherwise unsuccessful)
	 */
	public int addLog(String message, Date timestamp, LogSeverity severity) {
		return addLog(message, timestamp, severity, null);
	}

	/**
	 * The Log message that needs to be added to the Watson IoT Platform.
	 *
	 * @param message The Log message that needs to be added to the Watson IoT Platform.
	 * @param timestamp The Log timestamp
	 * @param severity The Log severity
	 * @param data The optional diagnostic string data -
	 *             The library will encode the data in base64 format as required by the Platform
	 * @return code indicating whether the update is successful or not
	 *        (200 means success, otherwise unsuccessful)
	 */
	public int addLog(String message, Date timestamp, LogSeverity severity, String data) {
		JsonObject jsonData = new JsonObject();
		JsonObject log = new JsonObject();
		log.add("message", new JsonPrimitive(message));
		log.add("severity", new JsonPrimitive(severity.getSeverity()));
		String utcTime = DateFormatUtils.formatUTC(timestamp,
				DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.getPattern());
		log.add("timestamp", new JsonPrimitive(utcTime));

		if(data != null) {
			byte[] encodedBytes = Base64.encodeBase64(data.getBytes());
			log.add("data", new JsonPrimitive(new String(encodedBytes)));
		}
		jsonData.add("d", log);

		try {
			JsonObject response = sendAndWait(client.getDMAgentTopic().getAddDiagLogTopic(),
					jsonData, REGISTER_TIMEOUT_VALUE);
			if (response != null ) {
				return response.get("rc").getAsInt();
			}
		} catch (MqttException e) {
			LOG.warn(e.toString());
		}
		return 0;
	}

	/**
	 * Moves the device from managed state to unmanaged state
	 *
	 * A device uses this request when it no longer needs to be managed.
	 * This means Watson IoT Platform will no longer send new device management requests
	 * to this device and device management requests from this device will
	 * be rejected apart from a Manage device request
	 *
	 * @return
	 * 		True if the unmanage command is successful

	 * @throws MqttException When failure
	 */
	public boolean sendUnmanageRequest() throws MqttException {
		boolean success = false;
		String topic = client.getDMAgentTopic().getUnmanageTopic();

		JsonObject jsonPayload = new JsonObject();
		JsonObject jsonResponse = sendAndWait(topic, jsonPayload, REGISTER_TIMEOUT_VALUE);
		if (jsonResponse != null && jsonResponse.get("rc").getAsInt() ==
				ResponseCode.DM_SUCCESS.getCode()) {
			success = true;
		}

		terminate();
		DMRequestHandler.clearRequestHandlers(this.client);
		this.terminateHandlers();

		if (responseSubscription != null) {
			this.unsubscribe(this.responseSubscription);
			responseSubscription = null;
		}

		LOG.debug("Success (" + success + ")");
		if(success) {
			bManaged = false;
		}
		return success;
	}

	/**
	 * <p>Subscribe the given listener to the given topic</p>
	 *
	 * <p> This method is used by the library to subscribe to each of the topic
	 * where IBM Watson IoT Platform will send the DM requests</p>
	 *
	 * @param topic topic to be subscribed
	 * @param qos Quality of Service for the subscription
	 * @param listener The IMqttMessageListener for the given topic

	 * @throws MqttException When subscription fails
	 */
	public void subscribe(String topic, int qos, IMqttMessageListener listener) throws MqttException {
		LOG.debug("Topic(" + topic + ")");
		if (isConnected()) {
			if (mqttAsyncClient != null) {
				mqttAsyncClient.subscribe(topic, qos, listener).waitForCompletion(DEFAULT_ACTION_TIMEOUT);
			} else if(mqttClient != null) {
				mqttClient.subscribe(topic, qos, listener);
			}
		} else {
			LOG.warn("Will not subscribe to topic(" + topic + ") because MQTT client is not connected.");
		}
	}

	/**
	 * <p>Subscribe the given listeners to the given topics</p>
	 *
	 * <p> This method is used by the library to subscribe to each of the topic
	 * where IBM Watson IoT Platform will send the DM requests</p>
	 *
	 * @param topics List of topics to be subscribed
	 * @param qos Quality of Service for the subscription
	 * @param listeners The list of IMqttMessageListeners for the given topics
	 * @throws MqttException When subscription fails
	 */
	public void subscribe(String[] topics, int[] qos, IMqttMessageListener[] listeners) throws MqttException {
		LOG.debug("Topics(" + topics + ")");
		if (isConnected()) {
			if (mqttAsyncClient != null) {
				mqttAsyncClient.subscribe(topics, qos, listeners).waitForCompletion();
			} else if(mqttClient != null) {
				mqttClient.subscribe(topics, qos, listeners);
			}
		} else {
			LOG.warn("Will not subscribe to topics(" + topics + ") because MQTT client is not connected.");
		}
	}

	/**
	 * <p>UnSubscribe the library from the given topic</p>
	 *
	 * <p> This method is used by the library to unsubscribe each of the topic
	 * where IBM Watson IoT Platform will send the DM requests</p>
	 *
	 * @param topic topic to be unsubscribed
	 * @throws MqttException when unsubscribe fails
	 */
	public void unsubscribe(String topic) throws MqttException {
		LOG.debug("Topic(" + topic + ")");
		if (isConnected()) {
			if (mqttAsyncClient != null) {
				mqttAsyncClient.unsubscribe(topic).waitForCompletion(DEFAULT_ACTION_TIMEOUT);
			} else if (mqttClient != null) {
				mqttClient.unsubscribe(topic);
			}
		} else {
			LOG.warn("Will not unsubscribe from topic(" + topic + ") because MQTT client is not connected.");
		}
	}

	/**
	 * <p>UnSubscribe the library from the given topics</p>
	 *
	 * <p> This method is used by the library to unsubscribe each of the topic
	 * where IBM Watson IoT Platform will send the DM requests</p>
	 *
	 * @param topics topics to be unsubscribed
	 * @throws MqttException when unsubscribe fails
	 */
	public void unsubscribe(String[] topics) throws MqttException {
		LOG.debug("Topics(" + topics + ")");
		if (isConnected()) {
			if (mqttAsyncClient != null) {
				mqttAsyncClient.unsubscribe(topics).waitForCompletion(DEFAULT_ACTION_TIMEOUT);
			} else if (mqttClient != null) {
				mqttClient.unsubscribe(topics);
			}
		} else {
			LOG.warn("Will not unsubscribe from topics(" + topics + ") because MQTT client is not connected.");
		}
	}

	protected IMqttDeliveryToken publish(String topic, MqttMessage message) throws MqttException {
		IMqttDeliveryToken token = null;
		LOG.debug("Topic(" + topic + ")");
		while(true) {
			if (isConnected()) {
				try {
					if (this.mqttAsyncClient != null) {
						token = mqttAsyncClient.publish(topic, message);
					} else if (mqttClient != null) {
						mqttClient.publish(topic, message);
					}
				} catch(MqttException ex) {
					long wait;
					switch (ex.getReasonCode()) {
					case MqttException.REASON_CODE_CLIENT_NOT_CONNECTED:
					case MqttException.REASON_CODE_CLIENT_DISCONNECTING:
						try {
							LOG.warn(" Connection Lost retrying to publish MSG :" + new String(message.getPayload(), "UTF-8") + " on topic "+topic+" every 5 seconds");
						} catch (UnsupportedEncodingException e1) {
							e1.printStackTrace();
						}
						wait = 5 * 1000;
						break;
					case MqttException.REASON_CODE_MAX_INFLIGHT:
						wait = 50;
						break;
					default:
						throw ex;
					}
					// Retry
					try {
						Thread.sleep(wait);
						continue;
					} catch (InterruptedException e) {}
				}

				if (isConnected() == false) {
					LOG.warn("MQTT got disconnected after publish to Topic(" + topic + ")");
				}
				return token;
			} else {
				LOG.warn("Will not publish to topic(" + topic + ") because MQTT client is not connected.");
				try {
					Thread.sleep(5 * 1000);
					continue;
				} catch (InterruptedException e) {}
			}
		}

	}

	/**
	 * <p>Publish the Device management response to IBm Watson IoT Platform </p>
	 *
	 * <p>This method is used by the library to respond to each of the Device Management commands from
	 *  IBM Watson IoT Platform</p>
	 *
	 * @param topic Topic where the response to be published
	 * @param payload the Payload
	 * @param qos The Quality Of Service
	 * @throws MqttException When MQTT operation fails
	 */
	public void publish(String topic, JsonObject payload, int qos) throws MqttException {
		JsonObject jsonPubMsg = new JsonObject();
		jsonPubMsg.addProperty("topic", topic);
		jsonPubMsg.add("qos", new JsonPrimitive(qos));
		jsonPubMsg.add("payload", payload);
		publishQueue.add(jsonPubMsg);
		LOG.debug("Queued Topic(" + topic + ") qos=" + qos + " payload (" + payload.toString() + ")");
	}
	
	public void publish(String topic, JsonObject payload) throws MqttException {
		publish(topic, payload, 1);
	}

	private void publish(JsonObject jsonPubMsg) throws MqttException, UnsupportedEncodingException {
		String topic = jsonPubMsg.get("topic").getAsString();
		int qos = jsonPubMsg.get("qos").getAsInt();
		JsonObject payload = jsonPubMsg.getAsJsonObject("payload");
		LOG.debug("Topic(" + topic + ") qos=" + qos + " payload (" + payload.toString() + ")");
		MqttMessage message = new MqttMessage();
		message.setPayload(payload.toString().getBytes("UTF-8"));
		message.setQos(qos);
		publish(topic, message);
	}

	/**
	 * <p>Send the message and waits for the response from IBM Watson IoT Platform</p>
	 *
	 * <p>This method is used by the library to send following messages to
	 *  IBM Watson IoT Platform</p>
	 *
	 *  <ul class="simple">
	 * <li>Manage
	 * <li>Unmanage
	 * <li>Location update
	 * <li>Diagnostic update/clear
	 * </ul>
	 *
	 * @param topic Topic where the message to be sent
	 * @param jsonPayload The message
	 * @param timeout How long to wait for the response
	 * @return response in Json format
	 * @throws MqttException when MQTT operation fails
	 */
	public JsonObject sendAndWait(String topic, JsonObject jsonPayload, long timeout) throws MqttException {
		String uuid = UUID.randomUUID().toString();
		jsonPayload.add("reqId", new JsonPrimitive(uuid));

		LOG.debug("Topic (" + topic + ") payload (" + jsonPayload.toString() + ") reqId (" + uuid + ")" );

		if (responseSubscription == null) {
			responseSubscription = client.getDMServerTopic().getDMServerTopic();
			subscribe(responseSubscription, 1, this);
		}

		MqttMessage message = new MqttMessage();
		try {
			message.setPayload(jsonPayload.toString().getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			LOG.warn("Error setting payload for topic: " + topic, e);
			return null;
		}

		message.setQos(1);

		requests.put(uuid, message);

		publish(topic, message);

		JsonObject jsonResponse = null;
		while (jsonResponse == null) {
			try {
				jsonResponse = queue.poll(timeout, TimeUnit.MILLISECONDS);
				if (jsonResponse == null) {
					break;
				}
				if (jsonResponse.get("reqId").getAsString().equals(uuid)) {
					LOG.debug("This response is for me reqId:" + jsonResponse.toString() );
					break;
				} else {
					// This response is not for our request, put it back to the queue.
					LOG.warn("This response is NOT for me reqId:" + jsonResponse.toString() );
					queue.add(jsonResponse);
					jsonResponse = null;
				}
			} catch (InterruptedException e) {
				break;
			}
		}
		if (jsonResponse == null) {
			LOG.warn("NO RESPONSE from Watson IoT Platform for request: " + jsonPayload.toString());
			LOG.warn("Connected(" + isConnected() + ")");
		}
		return jsonResponse;
	}


	/**
	 * Disconnects from IBM Watson IoT Platform
	 */
	@Override
	public void disconnect() {
		if(this.bManaged == true) {
			try {
				sendUnmanageRequest();
			} catch (MqttException e) {
			}
			this.bManaged = false;
		}
		super.disconnect();
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) {
		if (topic.equals(this.client.getDMServerTopic().getDMServerTopic())) {
			LOG.debug("Received response from Watson IoT Platform, topic (" + topic + ")");

			try {
				String responsePayload = new String (message.getPayload(), "UTF-8");
				JsonObject jsonResponse = new JsonParser().parse(responsePayload).getAsJsonObject();
				try {
					String reqId = jsonResponse.get("reqId").getAsString();
					LOG.debug("reqId (" + reqId + "): " + jsonResponse.toString() );
					MqttMessage sentMsg = requests.remove(reqId);
					if (sentMsg != null) {
						queue.put(jsonResponse);
					}
				} catch (Exception e) {
					if (jsonResponse.get("reqId") == null) {
						LOG.warn("The response does not contain 'reqId' field (" + responsePayload + ")");
					} else {
						LOG.warn("Unexpected exception", e);
					}
				}
			} catch (UnsupportedEncodingException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		} else {
			LOG.warn("Unknown topic (" + topic + ")");
		}
	}

	@Override
	public void run() {
		running = true;
		LOG.debug("Running...");
		while (running) {
			try {
				JsonObject o = publishQueue.take();
				if (o.equals(dummy)) {
					LOG.debug("It is time to quit.");
				} else {
					publish(o);
				}
			} catch (Exception e) {
				LOG.warn(e.toString());
				e.printStackTrace();
				running = false;
			}
		}
		LOG.debug("Exiting...");
	}

	private void terminate() {
		running = false;
		try {
			if(publishQueue != null) {
				publishQueue.put(dummy);
			}
		} catch (InterruptedException e) {
		}
	}

	private class ManagedDeviceClient implements ManagedClient {

		private ManagedDevice dmClient;
		private DMAgentTopic deviceDMAgentTopic;
		private DMServerTopic deviceDMServerTopic;

		private ManagedDeviceClient(ManagedDevice dmClient) {
			this.dmClient = dmClient;
			deviceDMAgentTopic = DeviceDMAgentTopic.getInstance();
			deviceDMServerTopic = DeviceDMServerTopic.getInstance();
		}


		@Override
		public void subscribe(String topic, int qos,
				IMqttMessageListener iMqttMessageListener) throws MqttException {
			dmClient.subscribe(topic, qos, iMqttMessageListener);
		}

		@Override
		public void unsubscribe(String topic) throws MqttException {
			dmClient.unsubscribe(topic);
		}

		@Override
		public void publish(String response, JsonObject payload)
				throws MqttException {
			dmClient.publish(response, payload);
		}
		
		@Override
		public void publish(String response, JsonObject payload, int qos)
				throws MqttException {
			dmClient.publish(response, payload, qos);
		}

		@Override
		public DeviceData getDeviceData() {
			return dmClient.getDeviceData();
		}

		@Override
		public void subscribe(String[] topics, int[] qos,
				IMqttMessageListener[] listener) throws MqttException {
			dmClient.subscribe(topics, qos, listener);
		}

		@Override
		public void unsubscribe(String[] topics) throws MqttException {
			dmClient.unsubscribe(topics);
		}

		@Override
		public DMAgentTopic getDMAgentTopic() {
			return this.deviceDMAgentTopic;
		}

		@Override
		public DMServerTopic getDMServerTopic() {
			return this.deviceDMServerTopic;
		}


		@Override
		public DeviceActionHandler getActionHandler() {
			return dmClient.actionHandler;
		}
		
		@Override
		public DeviceFirmwareHandler getFirmwareHandler() {
			return dmClient.fwHandler;
		}


		@Override
		public CustomActionHandler getCustomActionHandler() {
			return dmClient.customActionHandler;
		}

	}

	/**
	 * <p>Adds a firmware handler for this device,
	 * that is of type {@link com.ibm.wiotp.sdk.devicemgmt.DeviceFirmwareHandler}</p>
	 *
	 * <p>If the device supports firmware update, the abstract class
	 * {@link com.ibm.wiotp.sdk.devicemgmt.DeviceFirmwareHandler} should be extended by the device code.
	 * The {@link com.ibm.wiotp.sdk.devicemgmt.DeviceFirmwareHandler#downloadFirmware} and
	 * {@link com.ibm.wiotp.sdk.devicemgmt.DeviceFirmwareHandler#updateFirmware}
	 * must be implemented to handle the firmware actions.</p>
	 *
	 * @param fwHandler {@link com.ibm.wiotp.sdk.devicemgmt.DeviceFirmwareHandler} that handles the Firmware actions
	 * @throws Exception throws an exception if a handler is already added
	 *
	 */
	public void addFirmwareHandler(DeviceFirmwareHandler fwHandler) throws Exception {
		if(this.fwHandler != null) {
			LOG.warn("Firmware Handler is already set, so can not add the new firmware handler !");
			throw new Exception("Firmware Handler is already set, so can not add the new firmware handler !");
		}
		this.fwHandler = fwHandler;
	}

	/**
	 * <p>Adds a device action handler which is of type {@link com.ibm.wiotp.sdk.devicemgmt.DeviceActionHandler}</p>
	 *
	 * <p>If the device supports device actions like reboot and factory reset,
	 * the abstract class {@link com.ibm.wiotp.sdk.devicemgmt.DeviceActionHandler}
	 * should be extended by the device code. The {@link com.ibm.wiotp.sdk.devicemgmt.DeviceActionHandler#handleReboot} and
	 * {@link com.ibm.wiotp.sdk.devicemgmt.DeviceActionHandler#handleFactoryReset}
	 * must be implemented to handle the actions.</p>
	 *
	 * @param actionHandler {@link com.ibm.wiotp.sdk.devicemgmt.DeviceActionHandler} that handles the Reboot and Factory reset actions
	 * @throws Exception throws an exception if a handler is already added
	 */
	public void addDeviceActionHandler(DeviceActionHandler actionHandler) throws Exception {
		if(this.actionHandler != null) {
			LOG.warn("Action Handler is already set, so can not add the new Action handler !");
			throw new Exception("Action Handler is already set, so can not add the new Action handler !");
		}
		this.actionHandler = actionHandler;
	}
	
	/**
	 * <p>Adds a device action handler which is of type {@link com.ibm.wiotp.sdk.devicemgmt.CustomActionHandler}</p>
	 * 
	 * <p>If a Gateway or Device supports custom actions, this abstract class {@link com.ibm.wiotp.sdk.devicemgmt.CustomActionHandler}
	 * should be extended by the Gateway or Device code.</p>  
	 * 
	 * <p>The method {@link com.ibm.wiotp.sdk.devicemgmt.CustomActionHandler#handleCustomAction}
	 * must be implemented by the subclass to handle the actions sent by the IBM Watson IoT Platform.</p>
	 * 
	 * @param actionHandler Handler to handle the custom action
	 * @throws Exception Thrown if an error occurs when setting action handler
	 */
	public void addCustomActionHandler(CustomActionHandler actionHandler) throws Exception {
		if(this.actionHandler != null) {
			LOG.warn("Custom Action Handler is already set, so can not add the new Custom Action handler !");
			throw new Exception("Custom Action Handler is already set, so can not add the new Custom Action handler !");
		}
		this.customActionHandler = actionHandler;
	}

	private void terminateHandlers() {
		fwHandler = null;
		actionHandler = null;
		customActionHandler = null;
	}
}
