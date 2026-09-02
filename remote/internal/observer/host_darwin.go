//go:build darwin

package observer

import (
	"fmt"
	"os"
	"strings"
	"syscall"
)

// HostStats 是 dashboard.host 帧的主机资源字段。
// 内存口径近似 Activity Monitor 的已使用内存：总页数 − free − speculative −
// pageable 缓存（macOS 26 没有 vm.stats.vm.v_page_* BSD 名）。
// 磁盘取 /System/Volumes/Data（/ 是只读系统快照卷，不代表真实占用）；
// load 取 1 分钟值（精确 CPU% 需差分采样，二期）。
type HostStats struct {
	MemUsedPercent  int    `json:"memUsedPercent"`
	DiskUsedPercent int    `json:"diskUsedPercent"`
	Load1           string `json:"load1"`
}

// ReadHostStats 采集主机资源指标（macOS）。
func ReadHostStats() (HostStats, error) {
	mem, err := memUsedPercent()
	if err != nil {
		return HostStats{}, err
	}
	disk, err := diskUsedPercent("/System/Volumes/Data")
	if err != nil {
		return HostStats{}, err
	}
	load, loadErr := Load1()
	if loadErr != nil {
		return HostStats{}, loadErr
	}
	return HostStats{MemUsedPercent: mem, DiskUsedPercent: disk, Load1: load}, nil
}

func memUsedPercent() (int, error) {
	totalStr, err := syscall.Sysctl("hw.memsize")
	if err != nil {
		return 0, err
	}
	// Sysctl 返回的数值串会在首个 NUL 截断；小端解码时缺失的高位字节本就是 0。
	totalPages := uint64(0)
	for i := 0; i < len(totalStr) && i < 8; i++ {
		totalPages |= uint64(totalStr[i]) << (8 * i)
	}
	totalPages /= uint64(os.Getpagesize())

	free := uint64(sysctlUint32OrZero("vm.page_free_count"))
	speculative := uint64(sysctlUint32OrZero("vm.page_speculative_count"))
	pageable := uint64(sysctlUint32OrZero("vm.page_pageable_internal_count")) +
		uint64(sysctlUint32OrZero("vm.page_pageable_external_count"))
	unused := free + speculative + pageable
	if unused >= totalPages {
		return 0, nil
	}
	used := 100 * float64(totalPages-unused) / float64(totalPages)
	return int(used + 0.5), nil
}

func sysctlUint32OrZero(name string) uint32 {
	v, err := syscall.SysctlUint32(name)
	if err != nil {
		return 0
	}
	return v
}

func diskUsedPercent(path string) (int, error) {
	stat := &syscall.Statfs_t{}
	if err := syscall.Statfs(path, stat); err != nil {
		return 0, err
	}
	// 与 df 的 capacity 同口径：used/(used+avail)。
	if stat.Blocks == 0 {
		return 0, fmt.Errorf("statfs blocks is zero")
	}
	used := 100 * float64(stat.Blocks-stat.Bavail) / float64(stat.Blocks)
	return int(used + 0.5), nil
}

// Load1 返回 1 分钟负载。vm.loadavg 的 Sysctl 返回二进制 struct loadavg：
// 前 3 个 uint32 为定点数（FSCALE=65536），后接 timeval。
func Load1() (string, error) {
	raw, err := syscall.Sysctl("vm.loadavg")
	if err != nil {
		return "", err
	}
	if len(raw) < 4 {
		return "", fmt.Errorf("vm.loadavg too short")
	}
	load := float64(uint32(raw[0])|uint32(raw[1])<<8|uint32(raw[2])<<16|uint32(raw[3])<<24) / 65536.0
	return fmt.Sprintf("%.2f", load), nil
}

// ParseLoad1 从 vm.loadavg 原始串取 1 分钟负载（纯函数，供测试）。
func ParseLoad1(raw string) (string, bool) {
	fields := strings.Fields(raw)
	if len(fields) == 0 {
		return "", false
	}
	return fields[0], true
}
