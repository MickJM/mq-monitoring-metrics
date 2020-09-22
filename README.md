# MQ Metrics API

## MQ Exporter for Prometheus monitoring

This repository contains Java Spring Boot, microservice code for a monitoring solution that exports queue manager metrics to a Prometheus data collection system.  It also contains example configuration files on how to run the monitoring program.

The monitor collects metrics from an IBM MQ v9, v8 or v7 queue manager.  The monitor, polls metrics from the queue manager every 10 seconds, which can be changed in the configuration file.  Prometheus can be configured to call the exposed end-point at regular intervals to pull these metrics into its database, where they can be queried directly or used with dashboard applications such as Grafana.

The API can be run as a service or from a Docker container.

## Configure IBM MQ

The API can be run in three ways;

* Local binding connection
* Client connection
* Client Channel Defintion Table connection

### Local Binding connections

When running with a local binding connection, the API and the queue manager must be running on the same host.  The API connects directly to the queue manager.  No security or authentication is required, as the API is deemed to be authenticated due to it running on the same host.

```
ibm.mq.queueManager: QMGR
ibm.mq.local: true
```

No additional queue manager properties are required, but the APIs common properties can still ne used.

### Client Connections

When running as a client connection, the API and the queue manager run on seperate servers, the API connects to the queue manager over a network.  The queue manager must be configured to expose a running MQ listener, have a configured server-connection channel and the appropriate authorities set against the MQ objects (queue manager, queues, channels etc) to issue PCF commands.

Minimum yaml requirements in the application-XXXX.yaml file

```
ibm.mq.queueManager: QMGR
ibm.mq.channel: SVRCONN.CHANNEL.NAME
ibm.mq.connName: HOSTNAME(PORT)
ibm.mq.user: MQUser
ibm.mq.password: Password
ibm.mq.authenticateUsingCSP: true
ibm.mq.local: false
```
Connections to the queue manager should be encrpyted where possible.  For this, the queue manager needs to be configured with a key-store / trust-store - which can be the same file - and the server-connection channel needs to be configured with a cipher.

```
ibm.mq.useSSL: true
ibm.mq.sslCipherSpec: TLS_RSA_WITH_AES_128_CBC_SHA256
ibm.mq.security.truststore: full qualified folder / truststore 
ibm.mq.security.truststore-password: passw0rd
ibm.mq.security.keystore: full qualified folder / keystore 
ibm.mq.security.keystore-password: password
```


### Client Channel Defintion Table (CCDT) connections

When running as a CCDT connection, this is similar to a client connection, with the client connection details stored in a secure, binary file.

All configurations are stored in the Spring Boot yaml or properties file, which it typically located in a `./config` folder under where the API jar file is run from.

## Common API properites

Additional properties can be used in the yaml file;

`logging.level.org.springfromwork: OFF` - Spring Framework logging
`logging.level.maersk.com: debug-level` - Maersk objects to debug

`debug-level` can be `OFF`, `INFO`, `DEBUG`, `WARN` or `TRACE`

`spring.security.user.name: username` - The username used to authenticate the API when being invoked
`spring.security.user.password: password` - The password used to authenticate the API when being invoked
