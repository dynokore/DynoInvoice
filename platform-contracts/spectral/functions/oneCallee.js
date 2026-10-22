// INV-T-8456: an x-sync-edge sequence names one callee. Spectral's schema function cannot
// compare two elements, so this function reports a sequence whose '<caller>-><callee>'
// elements do not all end in the same callee. A string value is not its concern.
module.exports = (targetVal) => {
  if (!Array.isArray(targetVal)) return;
  const callees = new Set(targetVal.map((e) => String(e).split('->').slice(1).join('->')));
  if (callees.size > 1) return [{ message: 'x-sync-edge sequence names two different callees' }];
};
