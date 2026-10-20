```mermaid
flowchart LR
    NodeMCU[NodeMCU + INA219] -- MQTT --> Broker[(MQTT Broker on Pi)]
    Broker --> Django[Django Server]
    Broker <--> App[Android App]
```
