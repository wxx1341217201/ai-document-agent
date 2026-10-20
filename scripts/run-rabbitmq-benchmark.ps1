# Compatibility entry point; all implementation and workload live under benchmarks/rabbitmq.
& (Join-Path $PSScriptRoot '../benchmarks/rabbitmq/run.ps1') @args
