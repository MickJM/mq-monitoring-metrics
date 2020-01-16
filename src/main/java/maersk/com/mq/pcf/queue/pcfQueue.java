package maersk.com.mq.pcf.queue;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Get queue details
 * 
 * 17/10/2019 - Amended MaxQueueDepth to maxQueueDepth, LastGetDateTime to lastGetDateTime, LastPutDateTime to lastPutDateTime
 * 17/10/2019 - Amended the calculation of the Epoch value for lastGetDateTime and lastPutDateTime
 * 
 */

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metrics.mqmetrics.MQBase.MQPCFConstants;

@Component
public class pcfQueue extends MQBase {

    private Logger log = Logger.getLogger(this.getClass());

	private String queueManager;
	
	@Value("${ibm.mq.objects.queues.exclude}")
    private String[] excludeQueues;
	@Value("${ibm.mq.objects.queues.include}")
    private String[] includeQueues;

	
    private int queueMonitoringFromQmgr;
    public int getQueueMonitoringFromQmgr() {
		return queueMonitoringFromQmgr;
    }
	public void setQueueMonitoringFromQmgr(int value) {
		this.queueMonitoringFromQmgr = value;
	}

    private Map<String,AtomicInteger>queueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>openInMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>openOutMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>maxQueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicLong>lastGetMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>lastPutMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicInteger>oldAgeMap = new HashMap<String,AtomicInteger>();    
    private Map<String,AtomicInteger>deQueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>enQueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>procMap = new HashMap<String,AtomicInteger>();

	protected static final String lookupQueDepth = MQPREFIX + "queueDepth";
	protected static final String lookupOpenIn = MQPREFIX + "openInputCount";
	protected static final String lookupOpenOut = MQPREFIX + "openOutputCount";
	protected static final String lookupMaxDepth = MQPREFIX + "maxQueueDepth";
	protected static final String lookupLastGetDateTime = MQPREFIX + "lastGetDateTime";
	protected static final String lookupLastPutDateTime = MQPREFIX + "lastPutDateTime";
	protected static final String lookupOldMsgAge = MQPREFIX + "oldestMsgAge";
	protected static final String lookupdeQueued = MQPREFIX + "deQueued";
	protected static final String lookupenQueued = MQPREFIX + "enQueued";
	protected static final String lookupQueueProcesses = MQPREFIX + "queueProcesses";
	
	protected static final String lookupPutCount = MQPREFIX + "putCount";
	protected static final String lookupPut1Count = MQPREFIX + "put1Count";
	protected static final String lookupPutBytes = MQPREFIX + "putBytes";

    private PCFMessageAgent messageAgent;

	//private int clearMetrics = 0;

	public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    
    }
	
    public pcfQueue() {    	
    }
    
    /*
     * Get the metrics for each queue that we want
     */
	public void updateQueueMetrics() throws MQException, IOException, MQDataException {

		if (this._debug) { log.info("pcfQueue: inquire queue request"); }

		/*
		 * Clear the metrics every 'x' iteration
		 */
		this.clearMetrics++;
		if (this.clearMetrics % CONST_clearMetrics == 0) {
			this.clearMetrics = 0;
			if (this._debug) {
				log.debug("Clearing queue metrics");
			}
			resetMetrics();
		}
		
		// 17/10/2019 Amended to include HH.mm.ss
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");

		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q);
		pcfRequest.addParameter(MQConstants.MQCA_Q_NAME, "*");
		pcfRequest.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL);		
        
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = this.messageAgent.send(pcfRequest);
		} catch (Exception e) {
			if (this._debug) { log.warn("pcfQueue: no response returned - " + e.getMessage()); }
			
		}
		if (this._debug) { log.info("pcfQueue: inquire queue response"); }

		/*
		 * For each response, get the MQ details
		 */
		for (PCFMessage pcfMsg : pcfResponse) {
			String queueName = null;
			try {
				queueName = pcfMsg.getStringParameterValue(MQConstants.MQCA_Q_NAME).trim();
				if (this._debug) { log.info("pcfQueue: queue name: " + queueName); }

				if (checkQueueNames(queueName)) {
				
					int qType = pcfMsg.getIntParameterValue(MQConstants.MQIA_Q_TYPE);
					if ((qType != MQConstants.MQQT_LOCAL) && (qType != MQConstants.MQQT_ALIAS)) {
						if (this._debug) { log.info("pcfQueue: not needed : "); }
						throw new Exception("Not needed");
					}
					
					int qUsage = 0;
					int value = 0;
					
	 				String queueType = GetQueueType(qType);
					String queueCluster = "";
					String queueUsage = "";
					
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue local"); }
						qUsage = pcfMsg.getIntParameterValue(MQConstants.MQIA_USAGE);
						queueUsage = "Normal";
						if (qUsage != MQConstants.MQUS_NORMAL) {
							queueUsage = "Transmission";
						}
						value = pcfMsg.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH);
						queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
	
					} else {
						if (this._debug) { log.info("pcfQueue: inquire queue alias"); }
						queueUsage = "Alias";
						queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
	
					}

					if (this._debug) { log.info("pcfQueue: inquire queue status"); }

					PCFMessage pcfInqStat = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
					pcfInqStat.addParameter(MQConstants.MQCA_Q_NAME, queueName);

					PCFMessage[] pcfResStat = null;
					PCFMessage[] pcfResResp = null;
					if (qType != MQConstants.MQQT_ALIAS) {
						pcfInqStat.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_STATUS);					
						pcfResStat = this.messageAgent.send(pcfInqStat);
						PCFMessage pcfReset = new PCFMessage(MQConstants.MQCMD_RESET_Q_STATS);
						pcfReset.addParameter(MQConstants.MQCA_Q_NAME, queueName);
						pcfResResp = this.messageAgent.send(pcfReset);
						if (this._debug) { log.info("pcfQueue: inquire queue status response"); }

					}
					
					// Queue depth
					if (this._debug) { log.info("pcfQueue: queue depth"); }
				//	DeleteMetricEntry(lookupQueDepth);
					AtomicInteger qdep = queueMap.get(lookupQueDepth + "_" + queueName);
					if (qdep == null) {
						queueMap.put(lookupQueDepth + "_" + queueName, meterRegistry.gauge(lookupQueDepth, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(value))
								);
					} else {
						qdep.set(value);
					}

					/*
					meterRegistry.gauge(lookupQueDepth, 
							Tags.of("queueManagerName", this.queueManager,
									"queueName", queueName,
									"queueType", queueType,
									"usage",queueUsage,
									"cluster",queueCluster
									)
							,value);
					*/
					
					// OpenInput count
					int openInvalue = 0;
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue input count"); }
						openInvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_INPUT_COUNT);
						AtomicInteger openIn = openInMap.get(lookupOpenIn + "_" + queueName);
						if (openIn == null) {
							openInMap.put(lookupOpenIn + "_" + queueName, meterRegistry.gauge(lookupOpenIn, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(openInvalue))
									);
						} else {
							openIn.set(openInvalue);
						}

						/*
						meterRegistry.gauge(lookupOpenIn, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,openInvalue);
						*/
					}

					// Open output count
					int openOutvalue = 0;
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue output count"); }
						openOutvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_OUTPUT_COUNT);
						AtomicInteger openOut = openOutMap.get(lookupOpenOut + "_" + queueName);
						if (openOut == null) {
							openOutMap.put(lookupOpenOut + "_" + queueName, meterRegistry.gauge(lookupOpenOut, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(openOutvalue))
									);
						} else {
							openOut.set(openOutvalue);
						}

						/*
						meterRegistry.gauge(lookupOpenOut, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,openOutvalue);
						*/
					}

					if ((openInvalue > 0) || (openOutvalue > 0) ) {
						ProcessQueueHandlers(queueName, queueCluster);
					}

					// Maximum queue depth
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue depth"); }
						value = pcfMsg.getIntParameterValue(MQConstants.MQIA_MAX_Q_DEPTH);
						AtomicInteger openMax = maxQueMap.get(lookupMaxDepth + "_" + queueName);
						if (openMax == null) {
							maxQueMap.put(lookupMaxDepth + "_" + queueName, meterRegistry.gauge(lookupMaxDepth, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(value))
									);
						} else {
							openMax.set(value);
						}

						/*
						meterRegistry.gauge(lookupMaxDepth, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,value);
						*/
					}

					
					// for dates / time - the queue manager or queue monitoring must be at least 'low'
					// MQMON_OFF 	- Monitoring data collection is turned off
					// MQMON_NONE	- Monitoring data collection is turned off for queues, regardless of their QueueMonitor attribute
					// MQMON_LOW	- Monitoring data collection is turned on, with low ratio of data collection
					// MQMON_MEDIUM	- Monitoring data collection is turned on, with moderate ratio of data collection
					// MQMON_HIGH	- Monitoring data collection is turned on, with high ratio of data collection
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue LAST GET DATE : " + queueName); }
						if (!((getQueueMonitoringFromQmgr() == MQConstants.MQMON_OFF) 
								|| (getQueueMonitoringFromQmgr() == MQConstants.MQMON_NONE))) {
							String lastGetDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_DATE);
							String lastGetTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_TIME);
							if (!(lastGetDate.equals(" ") && lastGetTime.equals(" "))) {
					
					// 17/10/2019 Amened to correctly calcuate the epoch value			
								Date dt = formatter.parse(lastGetDate + " " + lastGetTime);
								long ld = dt.getTime();
								// Last Get date and time
								AtomicLong getDate = lastGetMap.get(lookupLastGetDateTime + "_" + queueName);
								if (getDate == null) {
									lastGetMap.put(lookupLastGetDateTime + "_" + queueName, meterRegistry.gauge(lookupLastGetDateTime, 
											Tags.of("queueManagerName", this.queueManager,
													"queueName", queueName,
													"queueType", queueType,
													"usage",queueUsage,
													"cluster",queueCluster
													),
											new AtomicLong(ld))
											);
								} else {
									getDate.set(ld);
								}
									
								/*
								meterRegistry.gauge(lookupLastGetDateTime, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster
												)
										,ld);
								*/
							}
		
							String lastPutDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_PUT_DATE);
							String lastPutTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_PUT_TIME);
							if (!(lastPutDate.equals(" ") && lastPutTime.equals(" "))) {
								if (this._debug) { log.info("pcfQueue: inquire queue LAST PUT DATE : " + queueName); }
								
					// 17/10/2019 amended to correctly calculate the epoch value				
								Date dt = formatter.parse(lastPutDate + " " + lastPutTime);
								long ld = dt.getTime();
								// Last put date and time
								AtomicLong lastDate = lastPutMap.get(lookupLastPutDateTime + "_" + queueName);
								if (this._debug) { log.info("pcfQueue: lastDate : " + lastDate); }

								if (lastDate == null) {
									lastPutMap.put(lookupLastPutDateTime + "_" + queueName, meterRegistry.gauge(lookupLastPutDateTime, 
											Tags.of("queueManagerName", this.queueManager,
													"queueName", queueName,
													"queueType", queueType,
													"usage",queueUsage,
													"cluster",queueCluster
													),
											new AtomicLong(ld))
											);
								} else {
									lastDate.set(ld);
								}

								/*
								meterRegistry.gauge(lookupLastPutDateTime, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster
												)
										,ld);
								*/
							}										
							
							if (this._debug) { log.info("pcfQueue: inquire queue old-age"); }
							// Oldest message age
							int old = pcfResStat[0].getIntParameterValue(MQConstants.MQIACF_OLDEST_MSG_AGE);
							AtomicInteger oldAge = oldAgeMap.get(lookupOldMsgAge + "_" + queueName);
							if (oldAge == null) {
								lastPutMap.put(lookupOldMsgAge + "_" + queueName, meterRegistry.gauge(lookupOldMsgAge, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster
												),
										new AtomicLong(old))
										);
							} else {
								oldAge.set(old);
							}

							/*
							meterRegistry.gauge(lookupOldMsgAge, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster,
											"type","seconds"
											)
									,old);
							*/
						}
					}
					
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue de-queued"); }
						// Messages DeQueued
						int devalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_DEQ_COUNT);
						AtomicInteger deQue = deQueMap.get(lookupdeQueued + "_" + queueName);
						if (deQue == null) {
							deQueMap.put(lookupdeQueued + "_" + queueName, meterRegistry.gauge(lookupdeQueued, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(devalue))
									);
						} else {
							deQue.set(devalue);
						}

						/*
						meterRegistry.gauge(lookupdeQueued, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,devalue);
						*/
					}

					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue en-queued"); }
						// Messages EnQueued
						int envalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_ENQ_COUNT);
						AtomicInteger enQue = enQueMap.get(lookupenQueued + "_" + queueName);
						if (enQue == null) {
							enQueMap.put(lookupenQueued + "_" + queueName, meterRegistry.gauge(lookupenQueued, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(envalue))
									);
						} else {
							enQue.set(envalue);
						}

						/*
						meterRegistry.gauge(lookupenQueued, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,envalue);
						*/
					}

				}
				
			} catch (Exception e) {
				if (this._debug) { log.warn("pcfQueue: unable to get queue metrcis " + e.getMessage()); }
				
			}
		}
	}

	/*
	 * Get the handles from each queue
	 */
	private void ProcessQueueHandlers(String queueName, String cluster ) throws MQException, IOException, MQDataException {
		
		PCFMessage pcfInqHandle = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
		pcfInqHandle.addParameter(MQConstants.MQCA_Q_NAME, queueName);
		pcfInqHandle.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_HANDLE);					
		PCFMessage[] pcfResHandle = this.messageAgent.send(pcfInqHandle);

		int seq = 0;
		for (PCFMessage pcfMsg : pcfResHandle) {
			int state = pcfMsg.getIntParameterValue(MQConstants.MQIACF_HANDLE_STATE);		
			String conn = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).trim();
			String appName = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_APPL_TAG).trim();			
			String userId = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_USER_IDENTIFIER).trim();			

			AtomicInteger proc = procMap.get(lookupQueueProcesses + "_" + appName.trim() + "_" + seq);
			if (proc == null) {
				procMap.put(lookupQueueProcesses + "_" + appName.trim() + "_" + seq, 
						meterRegistry.gauge(lookupQueueProcesses, 
						Tags.of("queueManagerName", this.queueManager,
								"cluster",cluster,
								"queueName", queueName.trim(),
								"application", appName.trim(),
								"user", userId,
								"seq",Integer.toString(seq)
								),
						new AtomicInteger(state))
						);
			} else {
				proc.set(state);
			}
			
			/*
			meterRegistry.gauge(lookupQueueProcesses, 
					Tags.of("queueManagerName", this.queueManager,
							"cluster",cluster,
							"queueName", queueName.trim(),
							"application", appName.trim(),
							"user", userId,
							"seq",Integer.toString(seq)
							)
					, state);
			*/
			seq++;
		}
				
	}
	
	
	/*
	 * Check for the queue names
	 */
	private boolean checkQueueNames(String name) {

		if (name.equals(null)) {
			return false;
		}
		
		// Exclude ...
		for (String s : this.excludeQueues) {
			if (s.equals("*")) {
				break;
			} else {
				if (name.startsWith(s)) {
					return false;
				}
			}
		}
	
		// Check queues against the list 
		for (String s : this.includeQueues) {
			if (s.equals("*")) {
				return true;
			} else {
				if (name.startsWith(s)) {
					return true;
				}				
			}
		}		
		return false;
	}

	/*
	 * Queue types
	 */
	private String GetQueueType(int qType) {

		String queueType = "";
		switch (qType) {
			case MQConstants.MQQT_ALIAS:
			{
				queueType = "Alias";
				break;
			}
			case MQConstants.MQQT_LOCAL:
			{
				queueType = "Local";
				break;
			}
			case MQConstants.MQQT_REMOTE:
			{
				queueType = "Remote";
				break;
			}
			case MQConstants.MQQT_MODEL:
			{
				queueType = "Model";
				break;
			}
			case MQConstants.MQQT_CLUSTER:
			{
				queueType = "Cluster";
				break;
			}
			
			default:
			{
				queueType = "Local";
				break;
			}
		}

		return queueType;
	}

	/*
	 * Allow access to reset metrics
	 */
	public void resetMetrics() {
		if (this._debug) { log.debug("pcfQueue: resetting metrics"); }
		deleteMetrics();
	}
	
	/*
	 * Clear the metrics ....
	 */
	private void deleteMetrics() {

		
		DeleteMetricEntry(lookupQueDepth);
		DeleteMetricEntry(lookupOpenIn);
		DeleteMetricEntry(lookupOpenOut);		
		DeleteMetricEntry(lookupMaxDepth);		
		DeleteMetricEntry(lookupLastGetDateTime);
		DeleteMetricEntry(lookupLastPutDateTime);
		DeleteMetricEntry(lookupOldMsgAge);
		DeleteMetricEntry(lookupdeQueued);
		DeleteMetricEntry(lookupenQueued);
		DeleteMetricEntry(lookupQueueProcesses);
		
		
	    this.queueMap.clear();
	    this.openInMap.clear();
	    this.openOutMap.clear();
	    this.maxQueMap.clear();
	    this.lastGetMap.clear();
	    this.lastPutMap.clear();
	    this.oldAgeMap.clear();
	    this.deQueMap.clear();
	    this.enQueMap.clear();
	    this.procMap.clear();
		
		
	}
	
}
