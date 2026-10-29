package imagegen

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"testing"
)

func TestEncodeJPEGKeepsAcceptedSizesAndDownscalesUnexpectedOnes(t *testing.T) {
	// Sizes the protocol accepts never need downscaling.
	accepted := image.NewRGBA(image.Rect(0, 0, 1024, 1536))
	data, width, height, err := encodeJPEG(accepted)
	if err != nil {
		t.Fatalf("encodeJPEG accepted size: %v", err)
	}
	if width != 1024 || height != 1536 {
		t.Fatalf("dimensions = %dx%d", width, height)
	}
	if len(data) < 3 || data[0] != 0xFF || data[1] != 0xD8 || data[2] != 0xFF {
		t.Fatal("result is not a JPEG")
	}

	// An unexpected 3000x1500 response is capped at 2048 px on the long edge.
	unexpected := image.NewRGBA(image.Rect(0, 0, 3000, 1500))
	for x := 0; x < 3000; x += 11 {
		unexpected.Set(x, x/2, color.RGBA{R: 240, G: 120, B: 30, A: 255})
	}
	data, width, height, err = encodeJPEG(unexpected)
	if err != nil {
		t.Fatalf("encodeJPEG oversized: %v", err)
	}
	if width != 2048 || height != 1024 {
		t.Fatalf("scaled dimensions = %dx%d", width, height)
	}
	decoded, err := jpeg.Decode(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("decode scaled JPEG: %v", err)
	}
	if decoded.Bounds().Dx() != 2048 || decoded.Bounds().Dy() != 1024 {
		t.Fatalf("decoded dimensions = %dx%d", decoded.Bounds().Dx(), decoded.Bounds().Dy())
	}
}
