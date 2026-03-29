//go:build unix

package logging

import (
	"log/syslog"
)

func newSyslogWriter(serviceName string) (*lineWriter, error) {
	writer, err := syslog.New(syslog.LOG_INFO|syslog.LOG_DAEMON, serviceName)
	if err != nil {
		return nil, err
	}

	return &lineWriter{
		writeLine: func(line string) error {
			return writer.Info(line)
		},
		closeFn: writer.Close,
	}, nil
}
