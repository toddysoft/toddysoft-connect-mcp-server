# ToddySoft Connect MCP Server

An [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) server that exposes
industrial PLC drivers as tools an AI assistant can call — letting a model **discover**,
**browse**, **read**, and **write** tags on real automation hardware (S7, Modbus, OPC UA,
EtherNet/IP, KNX, PROFINET, ADS, C-Bus, Firmata, …) through one uniform interface.

It is a [Spring Boot](https://spring.io/projects/spring-boot) application built on
[Spring AI](https://spring.io/projects/spring-ai)'s MCP server support, speaking the MCP
**stdio** transport so it plugs directly into MCP-capable clients (Claude Desktop, IDE
assistants, and other MCP hosts).

> **This is the open-source edition. It runs entirely on [Apache PLC4X](https://plc4x.apache.org/)
> drivers and is licensed under the GNU GPL v3.** For the commercially-supported ToddySoft
> Connect driver suite, see [Using the commercial drivers](#using-the-commercial-drivers)
> below.

## What it does

The server registers a small set of MCP tools:

| Tool          | Purpose                                                                    |
|---------------|----------------------------------------------------------------------------|
| `listDrivers` | List the PLC protocols/drivers available on the classpath                  |
| `discover`    | Discover reachable devices for a driver (where the protocol supports it)   |
| `browse`      | Browse the address/tag space of a connected device                         |
| `read`        | Read one or more tags                                                      |
| `write`       | Write one or more tags                                                     |

Connections are pooled through PLC4X's connection cache, and every tool call is recorded
through PLC4X's audit-log facility.

## Requirements

- **Java 17+**
- **Maven 3.9+**
- Network access to the PLC(s) you want to talk to

## Build

```bash
mvn clean package
```

This produces an executable Spring Boot JAR at
`target/toddysoft-connect-mcp-server-<version>.jar`.

> **Apache PLC4X version:** this project currently tracks `plc4x.version = 1.0.0-SNAPSHOT`
> (set in `pom.xml`). Until Apache PLC4X 1.0.0 is released to Maven Central you need the
> PLC4X snapshot in your local `~/.m2` (build PLC4X yourself, or add the ASF snapshot
> repository). Once 1.0.0 is released, change the property to `1.0.0` and it resolves from
> Central with no extra repositories.

## Run

The server communicates over **stdio**, so it is normally launched *by* an MCP client
rather than run standalone. Configure your MCP host to start it, e.g.:

```json
{
  "mcpServers": {
    "toddysoft-connect": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/target/toddysoft-connect-mcp-server-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

Runtime behaviour (timeouts, connection-cache tuning, server name/version) is configured in
[`src/main/resources/application.yml`](src/main/resources/application.yml).

## Choosing which drivers are exposed

The set of protocols the server offers is simply the set of PLC4X driver JARs on the
classpath. Each `plc4j-driver-*` dependency in [`pom.xml`](pom.xml) adds one protocol
(they register `org.apache.plc4x.java.api.PlcDriver` via the Java `ServiceLoader`). Add or
remove `plc4j-driver-*` dependencies to change the exposed protocol set — the full PLC4X
driver catalogue is listed in the [PLC4X documentation](https://plc4x.apache.org/users/protocols/index.html).

## About ToddySoft Connect

**ToddySoft Connect** is a commercial suite of industrial-protocol drivers built on the
Apache PLC4X foundation. Alongside the open-source protocols it adds drivers, hardening,
bug-fixes, and long-term support that go beyond the Apache project — for example additional
protocols and vendor variants, security-focused fixes, connection-pool recovery, and
per-driver licensing. This MCP server is the same server ToddySoft ships on top of that
suite, published here in an open-source form that runs on the Apache PLC4X drivers instead.

### Differences between Apache PLC4X and ToddySoft Connect

This open-source edition runs on the Apache PLC4X drivers. The commercial ToddySoft Connect
suite is built on the same PLC4X foundation, but adds protocols PLC4X does not cover and
extends several of the drivers PLC4X shares. The tables below summarize how the two compare,
driver by driver — only fully-implemented drivers are listed.

**Protocols ToddySoft Connect drives that Apache PLC4X does not:**

| Protocol / driver                   | What it is                                                   | Notes                                                                                                                                                                                                                                                      |
|-------------------------------------|--------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **SIMATIC Web API** (`s7-webapi`)   | Siemens S7-1200/1500 management plane over JSON-RPC/HTTPS    | Diagnostic buffer, alarms, operating-mode read/change and the controller's own account model — surfaces the process-data S7 driver cannot reach                                                                                                            |
| **IEC 61850** (`iec-61850`)         | Substation automation client / subscriber                    | MMS read/write/browse/report, control state machine (Direct / SBO / Enhanced-SBO), plus GOOSE and Sampled-Values subscription                                                                                                                              |
| **MTConnect** (`mtconnect`)         | Manufacturing-equipment telemetry                            | HTTP (poll + chunked streaming), MQTT and WebSocket transports; XML and JSON payloads; devices + assets                                                                                                                                                    |
| **PROFINET** (`profinet`)           | PROFINET IO controller — cyclic IO to PROFINET devices       | Connect/keep-alive handling validated against brownfield controllers, with reconnection handled at the connection-cache level. Apache PLC4X ships PROFINET drivers, but they are not currently operational, so in practice this protocol is ToddySoft-only |
| **EtherCAT** (`ethercat`)           | EtherCAT fieldbus master                                     | Slave scan, EEPROM/CoE mailbox, PDO mapping and cyclic process image (distributed-clocks bring-up for DC-mandatory hardware is in progress)                                                                                                                |
| **SunSpec** (`sunspec`)             | Solar inverters / DER over Modbus                            | Model-map discovery on top of the Modbus stack                                                                                                                                                                                                             |
| **Art-Net** (`artnet`)              | Art-Net 4 / DMX512 lighting control                          | UDP output to Art-Net nodes; node discovery; optional RDM tunnelling                                                                                                                                                                                       |
| **ctrlX** (`ctrlx`)                 | Bosch Rexroth ctrlX CORE — Data Layer over REST              | Apache PLC4X ships a ctrlX driver, but it is not currently operational, so in practice this protocol is ToddySoft-only                                                                                                                                     |
| **Open Protocol** (`open-protocol`) | Atlas Copco / Open Protocol tightening tools and controllers | Apache PLC4X ships an Open Protocol driver, but it is not currently operational, so in practice this protocol is ToddySoft-only                                                                                                                            |

(ToddySoft's `melsoft` driver covers the Mitsubishi MELSEC family, which PLC4X reaches through
its own `slmp` driver — so that hardware is served by both, under different names.)

**Where both suites have a driver — what the commercial edition adds:**

| Protocol                                        | Apache PLC4X                                         | ToddySoft Connect adds                                                                                                                                                                                                                                                                                                                                                            |
|-------------------------------------------------|------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S7**                                          | Classic S7comm (`0x32`) to S7-300/400/1200/1500      | A working **S7CommPlus** stack: **V3/TLS** to S7-1500 & S7-1200 G2, **password authentication**, native **cyclic subscriptions**, **alarming**, online browse, **S7-400H redundancy** with subscription survival across failover, and a `min-protocol`/`max-protocol` security window (plaintext-downgrade protection); plus an optimizer that greatly improves write performance |
| **ADS** (Beckhoff)                              | Symbol read/write/browse/subscribe against TwinCAT 3 | **TwinCAT 2** connectivity (PLC4X targets TwinCAT 3 only); reads **large symbol and data-type tables** that exceed a single ADS read, via automatic chunked uploads — needed on big PLC programs; **AoE** — EtherCAT slave telemetry (identity, AL state, CRC/frame counters, TwinSAFE FSoE) over the same ADS link; **TLS** transport; symbol-table reload                       |
| **UMAS** (Schneider Modicon)                    | Basic driver                                         | A much more complete UMAS implementation — reads and writes, subscriptions, and array / structure addressing                                                                                                                                                                                                                                                                      |
| **EtherNet/IP** (Allen-Bradley)                 | Read/write by symbolic tag                           | **Online tag browsing** of Allen-Bradley Logix controllers — discovers controller-scoped and program-scoped tags via the CIP Symbol object (class `0x6B`), cached for O(1) symbolic-name resolution                                                                                                                                                                               |
| **Modbus**                                      | Read/write with the standard function codes          | An optimizer that greatly improves write performance                                                                                                                                                                                                                                                                                                                              |
| **KNXnet/IP**, **IEC 60870-5-104**, **Firmata** | Full drivers                                         | Essentially on par — maintained ports on the shared ToddySoft licensing, connection-cache and audit-log infrastructure (KNXnet/IP, like PLC4X, refreshes its manufacturer-ID table at build time)                                                                                                                                                                                 |

**Protocols Apache PLC4X drives that ToddySoft Connect does not:** the open-source suite is
broader in a few areas, and for these this edition is the one to use.

| Protocol / driver                         | What it is                                                         |
|-------------------------------------------|--------------------------------------------------------------------|
| **OPC UA** (`opcua`)                      | OPC UA client — read/write/browse/subscribe against OPC UA servers |
| **Allen-Bradley DF1 / AB-ETH** (`ab-eth`) | Legacy Allen-Bradley PLCs over Ethernet                            |
| **Omron FINS**                            | Omron controllers over the FINS protocol                           |
| **CANopen / raw CAN** (`canopen`, `can`)  | CANopen and raw CAN bus access                                     |

Every ToddySoft driver additionally rides the suite's shared infrastructure: the pooling
connection cache with recovery, the audit log, and per-driver licensing. Which protocols and which of these extensions matter
is workload-specific — if PLC4X already covers your devices and the GPL fits your use, this
edition is all you need; if you need one of the protocols or extensions above, or non-GPL
terms, see [below](#using-the-commercial-drivers).

## About Apache PLC4X

[Apache PLC4X](https://plc4x.apache.org/) is a top-level Apache Software Foundation project
providing a unified API and a large catalogue of open-source drivers for industrial
protocols. This edition of the MCP server depends **only** on Apache PLC4X (licensed under
the Apache License 2.0) for all PLC communication — the driver implementations, the
connection cache, the audit log, and the value model are all PLC4X. If PLC4X covers your
protocols and your use case fits the GPL, you need nothing else here.

## Using the commercial drivers

If you want to:

- use the **commercially-supported ToddySoft Connect drivers** (extra protocols, vendor
  variants, security fixes, and support), or
- **embed this MCP server, or the drivers, in your own product** under terms other than the
  GPL (e.g. a proprietary/closed-source solution),

then the GPL open-source edition may not fit — reach out for a commercial offer:

**➡ [https://toddysoft.com](https://toddysoft.com)**

## License

Copyright (C) 2026 ToddySoft GmbH.

This program is free software: you can redistribute it and/or modify it under the terms of
the **GNU General Public License v3.0** as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version. See the [LICENSE](LICENSE)
file for the full text.

Apache PLC4X is a trademark of the Apache Software Foundation and is used here for
identification only; this project is not endorsed by or affiliated with the ASF.
