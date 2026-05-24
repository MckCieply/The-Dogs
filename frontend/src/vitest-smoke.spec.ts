// Verifies Vitest 4 runner configuration — no Angular TestBed dependency
describe('Vitest runner', () => {
  it('resolves globals and executes a trivial assertion', () => {
    expect(true).toBe(true);
  });
});
