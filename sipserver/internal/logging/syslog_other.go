//go:build !unix

package logging

func newSyslogWriter(serviceName string) (*lineWriter, error) {
	return nil, nil
}
