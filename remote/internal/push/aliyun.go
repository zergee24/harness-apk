package push

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

type Notifier interface {
	Notify(ctx context.Context, target, kind string) error
}

type Noop struct{}

func (Noop) Notify(context.Context, string, string) error { return nil }

type Aliyun struct {
	AccessKeyID     string
	AccessKeySecret string
	AppKey          int64
	Endpoint        string
	Client          *http.Client
}

func NewAliyunFromEnv(accessKeyID, accessKeySecret string, appKey int64) (*Aliyun, error) {
	if accessKeyID == "" || accessKeySecret == "" || appKey == 0 {
		return nil, errors.New("Aliyun push credentials are incomplete")
	}
	return &Aliyun{
		AccessKeyID: accessKeyID, AccessKeySecret: accessKeySecret, AppKey: appKey,
		Endpoint: "https://cloudpush.aliyuncs.com/", Client: &http.Client{Timeout: 10 * time.Second},
	}, nil
}

func (a *Aliyun) Notify(ctx context.Context, target, _ string) error {
	if target == "" {
		return nil
	}
	nonce := make([]byte, 16)
	if _, err := rand.Read(nonce); err != nil {
		return err
	}
	params := map[string]string{
		"AccessKeyId": a.AccessKeyID, "Action": "Push", "Format": "JSON",
		"SignatureMethod": "HMAC-SHA1", "SignatureNonce": hex.EncodeToString(nonce),
		"SignatureVersion": "1.0", "Timestamp": time.Now().UTC().Format("2006-01-02T15:04:05Z"),
		"Version": "2016-08-01", "AppKey": strconv.FormatInt(a.AppKey, 10),
		"Target": "DEVICE", "TargetValue": target, "DeviceType": "ANDROID",
		"PushType": "NOTICE", "Title": "Codex update", "Body": "Open Harness to view details",
		"AndroidNotifyType": "BOTH", "AndroidOpenType": "APPLICATION",
		"StoreOffline": "true",
	}
	params["Signature"] = sign(a.AccessKeySecret, params)
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, a.Endpoint, strings.NewReader(values(params).Encode()))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := a.Client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("Aliyun push returned %s", response.Status)
	}
	return nil
}

func sign(secret string, params map[string]string) string {
	encoded := values(params).Encode()
	canonical := "POST&%2F&" + percentEncode(encoded)
	mac := hmac.New(sha1.New, []byte(secret+"&"))
	_, _ = mac.Write([]byte(canonical))
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

func values(params map[string]string) url.Values {
	keys := make([]string, 0, len(params))
	for key := range params {
		if key != "Signature" {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	result := url.Values{}
	for _, key := range keys {
		result.Set(key, params[key])
	}
	if signature := params["Signature"]; signature != "" {
		result.Set("Signature", signature)
	}
	return result
}

func percentEncode(value string) string {
	replaced := url.QueryEscape(value)
	replaced = strings.ReplaceAll(replaced, "+", "%20")
	replaced = strings.ReplaceAll(replaced, "*", "%2A")
	return strings.ReplaceAll(replaced, "%7E", "~")
}
