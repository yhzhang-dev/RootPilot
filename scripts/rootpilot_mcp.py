#!/usr/bin/env python3
"""Zero-dependency stdio MCP bridge for RootPilot over adb-forwarded HTTP."""

import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE_URL = os.environ.get("ROOTPILOT_URL", "http://127.0.0.1:8765")
TOKEN = os.environ.get("ROOTPILOT_TOKEN", "")


def http(method, path, body=None, token=True):
    headers = {"Accept": "application/json"}
    if token:
        if not TOKEN:
            raise RuntimeError("ROOTPILOT_TOKEN is not set")
        headers["X-RootPilot-Token"] = TOKEN
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {payload}") from error


TOOLS = [
    {"name": "rootpilot_health", "description": "Check whether the phone Agent API is reachable.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "rootpilot_capabilities", "description": "List phone capabilities, permissions and safety state.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "rootpilot_status", "description": "Read device, battery, root, memory and storage status.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "rootpilot_location", "description": "Read the most recent phone location.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "rootpilot_notifications", "description": "List active captured notifications.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "rootpilot_events", "description": "Long-poll realtime phone events after an event id.", "inputSchema": {"type": "object", "properties": {"after": {"type": "integer", "default": 0}, "timeoutMs": {"type": "integer", "default": 25000}}}},
    {"name": "rootpilot_sensors", "description": "List sensors installed on the phone.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "rootpilot_sensor_sample", "description": "Get one sample from a sensor type.", "inputSchema": {"type": "object", "required": ["type"], "properties": {"type": {"type": "integer"}}}},
    {"name": "rootpilot_screenshot", "description": "Capture and return the current phone screen.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "rootpilot_observe", "description": "Return foreground app, screenshot and UI hierarchy together.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "rootpilot_act", "description": "Perform a typed UI action and return the resulting screenshot and UI tree.", "inputSchema": {"type": "object", "required": ["action"], "properties": {"action": {"type": "string", "enum": ["tap", "swipe", "key", "text", "launch"]}, "x": {"type": "integer"}, "y": {"type": "integer"}, "x1": {"type": "integer"}, "y1": {"type": "integer"}, "x2": {"type": "integer"}, "y2": {"type": "integer"}, "durationMs": {"type": "integer"}, "keyCode": {"type": "integer"}, "text": {"type": "string"}, "packageName": {"type": "string"}, "settleMs": {"type": "integer", "default": 350}}}},
    {"name": "rootpilot_subscription", "description": "Start or stop continuous location or sensor events.", "inputSchema": {"type": "object", "required": ["kind", "operation"], "properties": {"kind": {"type": "string", "enum": ["location", "sensor"]}, "operation": {"type": "string", "enum": ["start", "stop"]}, "type": {"type": "integer"}, "intervalMs": {"type": "integer"}}}},
]


def text_content(value):
    return [{"type": "text", "text": json.dumps(value, ensure_ascii=False, indent=2)}]


def observation_content(value):
    content = [{"type": "text", "text": json.dumps({k: v for k, v in value.items() if k not in ("screenshotBase64", "uiXml")}, ensure_ascii=False, indent=2)}]
    if value.get("screenshotBase64"):
        content.append({"type": "image", "mimeType": value.get("screenshotMimeType", "image/png"), "data": value["screenshotBase64"]})
    if value.get("uiXml"):
        content.append({"type": "text", "text": value["uiXml"]})
    return content


def call_tool(name, args):
    if name == "rootpilot_health":
        return text_content(http("GET", "/v1/health", token=False))
    if name == "rootpilot_capabilities":
        return text_content(http("GET", "/v1/capabilities"))
    if name == "rootpilot_status":
        return text_content(http("GET", "/v1/status"))
    if name == "rootpilot_location":
        return text_content(http("GET", "/v1/location/current"))
    if name == "rootpilot_notifications":
        return text_content(http("GET", "/v1/notifications"))
    if name == "rootpilot_events":
        query = urllib.parse.urlencode({"after": args.get("after", 0), "timeoutMs": args.get("timeoutMs", 25000)})
        return text_content(http("GET", "/v1/events?" + query))
    if name == "rootpilot_sensors":
        return text_content(http("GET", "/v1/sensors"))
    if name == "rootpilot_sensor_sample":
        return text_content(http("GET", "/v1/sensors/sample?type=" + str(args["type"])))
    if name == "rootpilot_screenshot":
        result = http("GET", "/v1/screen/screenshot")
        return [{"type": "image", "mimeType": result["mimeType"], "data": result["base64"]}]
    if name == "rootpilot_observe":
        return observation_content(http("GET", "/v1/automation/observe"))
    if name == "rootpilot_act":
        payload = dict(args)
        payload["observe"] = True
        result = http("POST", "/v1/automation/act", payload)
        content = [{"type": "text", "text": result.get("message", "ok")}]
        if result.get("observation"):
            content.extend(observation_content(result["observation"]))
        return content
    if name == "rootpilot_subscription":
        kind = args["kind"]
        operation = args["operation"]
        payload = {k: v for k, v in args.items() if k not in ("kind", "operation")}
        return text_content(http("POST", f"/v1/subscriptions/{kind}/{operation}", payload))
    raise RuntimeError(f"Unknown tool: {name}")


def respond(message):
    sys.stdout.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request = json.loads(line)
        method = request.get("method")
        request_id = request.get("id")
        if request_id is None:
            continue
        try:
            if method == "initialize":
                result = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}}, "serverInfo": {"name": "rootpilot", "version": "2.0"}}
            elif method == "tools/list":
                result = {"tools": TOOLS}
            elif method == "tools/call":
                params = request.get("params", {})
                result = {"content": call_tool(params.get("name", ""), params.get("arguments", {})), "isError": False}
            else:
                raise RuntimeError(f"Unsupported method: {method}")
            respond({"jsonrpc": "2.0", "id": request_id, "result": result})
        except Exception as error:
            respond({"jsonrpc": "2.0", "id": request_id, "error": {"code": -32000, "message": str(error)}})


if __name__ == "__main__":
    main()
