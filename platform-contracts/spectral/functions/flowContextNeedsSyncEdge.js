// INV-T-17619 (INV-Q-9241 B): an operation that carries x-flow-context (a system-flow operation that
// verifies a signed flow context) must also carry x-sync-edge. Spectral's `defined` function cannot
// be conditioned on another key, so this function reports an operation that has the marker and no
// x-sync-edge. An operation without the marker is not its concern.
module.exports = (operation) => {
  if (operation === null || typeof operation !== 'object' || Array.isArray(operation)) return;
  if (!Object.prototype.hasOwnProperty.call(operation, 'x-flow-context')) return;
  if (Object.prototype.hasOwnProperty.call(operation, 'x-sync-edge')) return;
  return [{ message: 'an operation with x-flow-context must also carry x-sync-edge (INV-Q-9241 B, API-08)' }];
};
