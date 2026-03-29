package logging

import (
	"io"
	"log"
	"log/slog"
	"os"
	"strings"
)

type nopCloser struct{}

func (nopCloser) Close() error {
	return nil
}

type lineWriter struct {
	writeLine func(string) error
	closeFn   func() error
	buffer    strings.Builder
}

func (w *lineWriter) Write(p []byte) (int, error) {
	for _, b := range p {
		if b == '\n' {
			if err := w.flush(); err != nil {
				return 0, err
			}
			continue
		}
		w.buffer.WriteByte(b)
	}
	return len(p), nil
}

func (w *lineWriter) Close() error {
	if err := w.flush(); err != nil {
		return err
	}
	if w.closeFn != nil {
		return w.closeFn()
	}
	return nil
}

func (w *lineWriter) flush() error {
	if w.buffer.Len() == 0 {
		return nil
	}
	line := w.buffer.String()
	w.buffer.Reset()
	return w.writeLine(line)
}

func New(serviceName string) (*slog.Logger, io.Closer) {
	output := io.Writer(os.Stdout)
	syslogWriter, err := newSyslogWriter(serviceName)
	if err == nil && syslogWriter != nil {
		output = io.MultiWriter(os.Stdout, syslogWriter)
	}

	log.SetOutput(output)
	log.SetFlags(0)

	logger := slog.New(slog.NewTextHandler(output, &slog.HandlerOptions{
		Level: slog.LevelDebug,
	}))

	if err != nil {
		logger.Warn("syslog unavailable, continuing with stdout logging only", "service", serviceName, "error", err)
	}

	if syslogWriter == nil {
		return logger, nopCloser{}
	}
	return logger, syslogWriter
}
