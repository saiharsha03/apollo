// Stand-in for the Samsung scene catalog, which is not in the public copy (the artwork is Samsung's).
// app-features.js still asks for these: an empty catalog and a scene that draws nothing, so the page keeps working
// with the illustrated scenes. See ../README.md.
window.SamsungSceneCatalog = [];
window.SamsungScene = { draw() {}, pick() { return ""; } };
