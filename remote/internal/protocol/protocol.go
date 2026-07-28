package protocol

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

const Version = 1

type PairingPayload struct {
	Version       int    `json:"version"`
	RelayURL      string `json:"relayUrl"`
	HostID        string `json:"hostId"`
	HostName      string `json:"hostName"`
	PairingTicket string `json:"pairingTicket"`
	PairingSecret string `json:"pairingSecret"`
	ExpiresAt     int64  `json:"expiresAt"`
}

type WireMessage struct {
	Version       int    `json:"version"`
	MessageID     string `json:"messageId"`
	HostID        string `json:"hostId"`
	DeviceID      string `json:"deviceId,omitempty"`
	PairingTicket string `json:"pairingTicket,omitempty"`
	Sequence      uint64 `json:"sequence"`
	ExpiresAt     int64  `json:"expiresAt"`
	Nonce         string `json:"nonce"`
	Ciphertext    string `json:"ciphertext"`
	PushKind      string `json:"pushKind,omitempty"`
}

type Command struct {
	Type            string          `json:"type"`
	RequestID       string          `json:"requestId"`
	ThreadID        string          `json:"threadId,omitempty"`
	TurnID          string          `json:"turnId,omitempty"`
	Text            string          `json:"text,omitempty"`
	CWD             string          `json:"cwd,omitempty"`
	ExpectedTurnID  string          `json:"expectedTurnId,omitempty"`
	ServerRequestID json.RawMessage `json:"serverRequestId,omitempty"`
	Decision        string          `json:"decision,omitempty"`
	Method          string          `json:"method,omitempty"`
	Params          json.RawMessage `json:"params,omitempty"`
}

type Event struct {
	Type      string          `json:"type"`
	RequestID string          `json:"requestId,omitempty"`
	Method    string          `json:"method,omitempty"`
	ThreadID  string          `json:"threadId,omitempty"`
	TurnID    string          `json:"turnId,omitempty"`
	Message   string          `json:"message,omitempty"`
	Payload   json.RawMessage `json:"payload,omitempty"`
	CreatedAt int64           `json:"createdAt"`
}

func NewID() (string, error) {
	raw := make([]byte, 18)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func NewSecret() ([]byte, error) {
	secret := make([]byte, 32)
	_, err := rand.Read(secret)
	return secret, err
}

func EncodeSecret(secret []byte) string { return base64.RawURLEncoding.EncodeToString(secret) }

func DecodeSecret(encoded string) ([]byte, error) {
	secret, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil || len(secret) != 32 {
		return nil, errors.New("pairing secret must be 32 bytes")
	}
	return secret, nil
}

func Encrypt(secret []byte, message WireMessage, plain any) (WireMessage, error) {
	block, err := aes.NewCipher(secret)
	if err != nil {
		return WireMessage{}, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return WireMessage{}, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return WireMessage{}, err
	}
	payload, err := json.Marshal(plain)
	if err != nil {
		return WireMessage{}, err
	}
	message.Version = Version
	if message.MessageID == "" {
		message.MessageID, err = NewID()
		if err != nil {
			return WireMessage{}, err
		}
	}
	if message.ExpiresAt == 0 {
		message.ExpiresAt = time.Now().Add(5 * time.Minute).UnixMilli()
	}
	message.Nonce = base64.RawURLEncoding.EncodeToString(nonce)
	sealed := gcm.Seal(nil, nonce, payload, []byte(message.aad()))
	message.Ciphertext = base64.RawURLEncoding.EncodeToString(sealed)
	return message, nil
}

func Decrypt(secret []byte, message WireMessage, target any) error {
	if message.Version != Version {
		return fmt.Errorf("unsupported protocol version %d", message.Version)
	}
	if message.ExpiresAt < time.Now().UnixMilli() {
		return errors.New("message expired")
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	nonce, err := base64.RawURLEncoding.DecodeString(message.Nonce)
	if err != nil || len(nonce) != gcm.NonceSize() {
		return errors.New("invalid nonce")
	}
	sealed, err := base64.RawURLEncoding.DecodeString(message.Ciphertext)
	if err != nil {
		return errors.New("invalid ciphertext")
	}
	plain, err := gcm.Open(nil, nonce, sealed, []byte(message.aad()))
	if err != nil {
		return errors.New("message authentication failed")
	}
	return json.Unmarshal(plain, target)
}

func (m WireMessage) aad() string {
	return fmt.Sprintf("%d\x00%s\x00%s\x00%s\x00%s\x00%d\x00%d\x00%s", m.Version, m.MessageID, m.HostID, m.DeviceID, m.PairingTicket, m.Sequence, m.ExpiresAt, m.PushKind)
}
