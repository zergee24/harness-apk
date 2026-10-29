package imagegen

import (
	"bytes"
	"image"
	"image/jpeg"
	"image/png"
)

// decodePNG decodes the upstream PNG so the bridge can report dimensions and
// re-encode it when the payload exceeds the relay frame budget.
func decodePNG(raw []byte) (image.Image, error) {
	decoded, err := png.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, &Error{Code: CodeDecodeFailed, Message: "无法解码 OpenAI 返回的 PNG 图片", Err: err}
	}
	return decoded, nil
}

// encodeJPEG re-encodes an image as JPEG q85 with the long edge capped at 2048
// px. The protocol only accepts sizes up to 1536 px on the long edge, so the
// downscale is a safety net for unexpected upstream dimensions.
func encodeJPEG(source image.Image) ([]byte, int, int, error) {
	bounds := source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	if width <= 0 || height <= 0 {
		return nil, 0, 0, &Error{Code: CodeEncodeFailed, Message: "图片尺寸无效"}
	}
	longEdge := width
	if height > longEdge {
		longEdge = height
	}
	if longEdge > maxLongEdge {
		width = scaledDimension(width, longEdge)
		height = scaledDimension(height, longEdge)
		source = scaleNearest(source, width, height)
	}
	var buffer bytes.Buffer
	if err := jpeg.Encode(&buffer, source, &jpeg.Options{Quality: jpegQuality}); err != nil {
		return nil, 0, 0, &Error{Code: CodeEncodeFailed, Message: "图片转码为 JPEG 失败", Err: err}
	}
	return buffer.Bytes(), width, height, nil
}

// scaledDimension rounds one edge to its proportional size, never below 1 px.
func scaledDimension(edge, longEdge int) int {
	scaled := (edge*maxLongEdge + longEdge/2) / longEdge
	if scaled < 1 {
		return 1
	}
	return scaled
}

// scaleNearest resamples with nearest-neighbour sampling, keeping the bridge on
// the standard library only.
func scaleNearest(source image.Image, width, height int) image.Image {
	bounds := source.Bounds()
	scaled := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		sourceY := bounds.Min.Y + y*bounds.Dy()/height
		for x := 0; x < width; x++ {
			sourceX := bounds.Min.X + x*bounds.Dx()/width
			scaled.Set(x, y, source.At(sourceX, sourceY))
		}
	}
	return scaled
}
