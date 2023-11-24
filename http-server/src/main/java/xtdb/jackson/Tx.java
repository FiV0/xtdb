package xtdb.jackson;

import xtdb.tx.Ops;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public record Tx(List<Ops> txOps, LocalDateTime systemTime, ZoneId defaultTz) {}
