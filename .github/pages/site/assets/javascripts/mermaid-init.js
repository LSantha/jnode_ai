document$.subscribe(function () {
    mermaid.initialize({ startOnLoad: false, theme: "default" });
    mermaid.run({ querySelector: ".mermaid" });
});
