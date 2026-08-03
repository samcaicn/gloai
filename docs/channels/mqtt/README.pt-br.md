# 📡 Canal MQTT

O colearn suporta qualquer cliente MQTT como canal de mensagens. Dispositivos ou serviços publicam requisições para um broker; o colearn assina, processa e publica as respostas de volta.

## 🚀 Início rápido

**1. Adicione o canal ao `~/.colearn/config.json`:**

```json
{
  "channel_list": {
    "mqtt": {
      "enabled": true,
      "type": "mqtt",
      "settings": {
        "broker": "tcp://www.tuptup.top:1883",
        "agent_id": "assistant"
      }
    }
  }
}
```

**2. Inicie o gateway:**

```bash
colearn gateway
```

**3. Envie uma mensagem de qualquer cliente MQTT:**

```bash
mosquitto_pub -t "/colearn/assistant/device1/request" \
  -m '{"text": "Qual é o uso de CPU?"}'
```

**4. Assine para receber a resposta:**

```bash
mosquitto_sub -t "/colearn/assistant/device1/response"
```

---

## 📨 Estrutura de tópicos

```
{prefix}/{agent_id}/{client_id}/request    # Cliente → colearn
{prefix}/{agent_id}/{client_id}/response   # colearn → Cliente
```

| Segmento | Descrição |
|----------|-----------|
| `prefix` | Prefixo do tópico configurado no servidor. Padrão: `/colearn` |
| `agent_id` | Identificador da instância do colearn, definido no campo `agent_id` |
| `client_id` | Identificador de sessão definido pelo cliente — use um ID estável por dispositivo para manter o contexto da conversa |

### Payload da mensagem (JSON)

```json
{ "text": "sua mensagem aqui" }
```

---

## ⚙️ Configuração

### config.json

```json
{
  "channel_list": {
    "mqtt": {
      "enabled": true,
      "type": "mqtt",
      "settings": {
        "broker": "ssl://seu-broker:8883",
        "agent_id": "assistant",
        "topic_prefix": "/colearn",
        "client_id": "",
        "keep_alive": 60,
        "qos": 0
      }
    }
  }
}
```

### .security.yml (credenciais)

O nome de usuário e a senha são armazenados em `~/.colearn/.security.yml`, não no `config.json`:

```yaml
channel_list:
  mqtt:
    settings:
      username: seu_usuario
      password: sua_senha
```

### Campos de configuração

| Campo | Local | Obrigatório | Padrão | Descrição |
|-------|-------|-------------|--------|-----------|
| `broker` | `settings` | Sim | — | URL do broker MQTT, ex. `tcp://host:1883`, `ssl://host:8883` |
| `agent_id` | `settings` | Sim | — | Identificador do agente, usado como parte do caminho do tópico |
| `topic_prefix` | `settings` | Não | `/colearn` | Prefixo do namespace dos tópicos |
| `username` | `.security.yml` | Não | — | Nome de usuário para autenticação no broker |
| `password` | `.security.yml` | Não | — | Senha para autenticação no broker |
| `client_id` | `settings` | Não | gerado automaticamente | ID de cliente paho enviado ao broker. Gerado automaticamente como `colearn-mqtt-{agent_id}-{8 hex}` se não definido; fixo durante o tempo de vida do processo e reutilizado nas reconexões |
| `keep_alive` | `settings` | Não | `60` | Intervalo de keepalive MQTT em segundos |
| `qos` | `settings` | Não | `0` | Nível de QoS para publicação e assinatura: `0`, `1` ou `2` |

### Variáveis de ambiente

| Variável | Campo |
|----------|-------|
| `colearn_CHANNELS_MQTT_BROKER` | `broker` |
| `colearn_CHANNELS_MQTT_AGENT_ID` | `agent_id` |
| `colearn_CHANNELS_MQTT_TOPIC_PREFIX` | `topic_prefix` |
| `colearn_CHANNELS_MQTT_USERNAME` | `username` |
| `colearn_CHANNELS_MQTT_PASSWORD` | `password` |
| `colearn_CHANNELS_MQTT_CLIENT_ID` | `client_id` |
| `colearn_CHANNELS_MQTT_KEEP_ALIVE` | `keep_alive` |
| `colearn_CHANNELS_MQTT_QOS` | `qos` |

---

## 🔄 Reconexão

O colearn reconecta automaticamente ao broker se a conexão for perdida, com intervalo de 5 segundos. Após a reconexão, a assinatura é restabelecida automaticamente. O ID de cliente no broker permanece o mesmo nas reconexões, permitindo que o broker identifique corretamente a mesma sessão.

---

## ⚠️ Observações

- **TLS**: SSL/TLS é suportado (URL do broker com `ssl://`). A verificação de certificado é ignorada por padrão.
- **Respostas em streaming**: Respostas em streaming enviam múltiplas mensagens para o tópico de resposta; concatene-as na ordem recebida para obter a resposta completa.
- **client_id vs ID de sessão**: O `client_id` no caminho do tópico é definido pela sua aplicação cliente e identifica a sessão. É separado do ID de cliente paho usado pelo colearn para se conectar ao broker.
- **Múltiplas instâncias**: Se várias instâncias do colearn usarem o mesmo `agent_id` no mesmo broker, defina `client_id` distintos para evitar conflitos no nível do broker.
