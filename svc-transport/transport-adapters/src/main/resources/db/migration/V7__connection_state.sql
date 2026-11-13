-- V7__connection_state.sql (INV-T-18675; INV-REQ-835, INV-REQ-836).
-- The stored health state of an AP connection: the circuit-breaker state after the last connection test (closed UP,
-- half-open DEGRADED, open DOWN). It starts UP, the state of a closed breaker, which is also where the shared status
-- reporter of service-runtime starts, so `ConnectionStatusChanged` is requested only when the state leaves it or
-- returns to it.
alter table transport.ap_connection
    add column connection_state text not null default 'UP'
        constraint ap_connection_connection_state_check check (connection_state in ('UP', 'DEGRADED', 'DOWN'));
