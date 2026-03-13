package main

/*
#include <stdlib.h>
#include <string.h>

// 回调函数类型定义
typedef void (*message_callback_t)(const char* msg);
static message_callback_t g_callback = NULL;

// 设置回调函数
void setMessageCallback(message_callback_t cb) {
    g_callback = cb;
}

// 调用回调函数（供 Go 代码使用）
void invokeCallback(const char* msg) {
    if (g_callback != NULL) {
        g_callback(msg);
    }
}
*/
import "C"
import (
	"encoding/json"
	"fmt"
	"unsafe"
)

// 全局配置和状态
type GoClawState struct {
	Config  string
	Running bool
}

var state = &GoClawState{
	Running: false,
}

//export GoClawInit
func GoClawInit(config *C.char) *C.char {
	cfg := C.GoString(config)
	state.Config = cfg
	state.Running = true

	// 解析配置
	var configMap map[string]interface{}
	if err := json.Unmarshal([]byte(cfg), &configMap); err != nil {
		return C.CString(fmt.Sprintf(`{"error": "invalid config: %v"}`, err))
	}

	// TODO: 初始化 goclaw 服务
	// 这里可以调用原有的 goclaw 初始化代码

	result := `{"status": "initialized", "config": "` + cfg + `"}`
	return C.CString(result)
}

//export GoClawStart
func GoClawStart() *C.char {
	if !state.Running {
		return C.CString(`{"error": "not initialized"}`)
	}

	// TODO: 启动 goclaw 服务
	state.Running = true

	return C.CString(`{"status": "started"}`)
}

//export GoClawStop
func GoClawStop() {
	state.Running = false
	// TODO: 停止 goclaw 服务
}

//export GoClawSendMessage
func GoClawSendMessage(message *C.char) *C.char {
	if !state.Running {
		return C.CString(`{"error": "service not running"}`)
	}

	msg := C.GoString(message)

	// TODO: 处理消息并返回结果
	// 这里可以调用原有的 goclaw 消息处理逻辑

	response := fmt.Sprintf(`{"received": "%s", "processed": true}`, msg)
	return C.CString(response)
}

//export GoClawGetStatus
func GoClawGetStatus() *C.char {
	status := fmt.Sprintf(`{"running": %v, "config": "%s"}`, state.Running, state.Config)
	return C.CString(status)
}

//export GoClawSetCallback
func GoClawSetCallback(callback C.message_callback_t) {
	C.setMessageCallback(callback)
}

//export GoClawFreeString
func GoClawFreeString(str *C.char) {
	C.free(unsafe.Pointer(str))
}

func main() {
	// 共享库不需要 main 函数，但必须提供
}
