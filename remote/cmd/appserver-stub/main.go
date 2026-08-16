// Command appserver-stub is a minimal canonical app-server for acceptance
// testing: it speaks the same stdio JSON-RPC surface as `codex app-server`
// (thread/list, thread/start, turn/start, turn/steer, thread/read,
// thread/turns/list) with scripted results and simulated streaming events.
// Use it as a second backend when the real codex binary is unavailable:
//
//	harness-bridge serve --backend codex --backend dsh
//	harness-bridge serve --backend codex --backend stub=/path/to/appserver-stub
//
// The stub answers every turn with a fixed message after emitting
// turn/started, item/agentMessage/delta and turn/completed notifications.
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"
)

type request struct {
	ID     any             `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}

func writeLine(value any) {
	raw, _ := json.Marshal(value)
	fmt.Println(string(raw))
}

func respond(id any, result any) { writeLine(map[string]any{"id": id, "result": result}) }
func respondError(id any, code, message string) {
	writeLine(map[string]any{"id": id, "error": map[string]string{"code": code, "message": message}})
}
func notify(method string, params any) { writeLine(map[string]any{"method": method, "params": params}) }

func paramString(params json.RawMessage, key string) string {
	var object map[string]any
	if json.Unmarshal(params, &object) != nil {
		return ""
	}
	value, _ := object[key].(string)
	return value
}

func main() {
	delayMillis := flag.Int("delay", 300, "simulated turn duration in milliseconds")
	flag.Parse()
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 64<<10), 4<<20)
	for scanner.Scan() {
		var req request
		if json.Unmarshal(scanner.Bytes(), &req) != nil || req.Method == "" {
			continue
		}
		switch req.Method {
		case "initialize":
			respond(req.ID, map[string]any{
				"protocolVersion": 1,
				"capabilities":    map[string]any{},
				"serverInfo":      map[string]string{"name": "appserver-stub", "version": "0.1.0"},
			})
		case "initialized":
			// no response expected
		case "thread/list":
			respond(req.ID, map[string]any{"data": []any{}})
		case "thread/start":
			now := time.Now().UnixMilli()
			respond(req.ID, map[string]any{
				"thread": map[string]any{
					"id": "stub-thread-" + fmt.Sprint(now%100000), "cwd": paramString(req.Params, "cwd"),
					"createdAt": now, "updatedAt": now,
				},
			})
		case "turn/start", "turn/steer":
			threadID := paramString(req.Params, "threadId")
			turnID := fmt.Sprintf("stub-turn-%d", time.Now().UnixMilli()%100000)
			notify("turn/started", map[string]any{"threadId": threadID, "turn": map[string]any{"id": turnID}})
			time.Sleep(time.Duration(*delayMillis) * time.Millisecond)
			notify("item/agentMessage/delta", map[string]any{
				"threadId": threadID, "itemId": "stub-item-1",
				"delta": "这是验收 stub 后端的固定回复。",
			})
			notify("turn/completed", map[string]any{
				"threadId": threadID, "turn": map[string]any{"id": turnID, "status": "completed"},
				"status": "completed", "reason": "completed",
			})
			respond(req.ID, map[string]any{"turn": map[string]any{"id": turnID, "threadId": threadID, "status": "completed"}})
		case "thread/read":
			respond(req.ID, map[string]any{"thread": map[string]any{
				"id": paramString(req.Params, "threadId"), "cwd": "/stub", "name": "Stub 会话",
				"status": map[string]any{"type": "idle"}, "turns": []any{},
			}})
		case "thread/turns/list":
			respond(req.ID, map[string]any{"data": []any{}, "nextCursor": nil})
		default:
			if strings.HasPrefix(req.Method, "thread/") || strings.HasPrefix(req.Method, "turn/") {
				respondError(req.ID, "unsupported", "stub does not implement "+req.Method)
				continue
			}
			respond(req.ID, map[string]any{"echo": req.Method})
		}
	}
}
