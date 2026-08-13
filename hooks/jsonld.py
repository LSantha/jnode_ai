import json


def _build_jsonld() -> str:
    site = {
        "@context": "https://schema.org",
        "@type": "WebSite",
        "name": "JNode",
        "url": "https://lsantha.github.io/jnode_ai/",
        "description": "JNode is an experimental Java operating system, developed with AI agents. It builds its own JVM, kernel, drivers, filesystems, network stack, and GUI.",
        "keywords": [
            "JNode",
            "Java operating system",
            "JVM",
            "kernel",
            "AI development",
            "agent-driven development",
            "OpenCode",
            "open source OS",
            "bare-metal",
        ],
        "publisher": {
            "@type": "Organization",
            "name": "JNode Project",
            "url": "https://github.com/LSantha/jnode_ai",
            "sameAs": [
                "https://github.com/LSantha/jnode_ai",
                "https://github.com/LSantha/jnode_ai/wiki",
            ],
        },
        "audience": {
            "@type": "Audience",
            "description": "Developers and researchers interested in OS design, JVM implementation, and AI-driven software development. This fork is maintained with agentic (AI) workflows using OpenCode agents and GitHub Actions.",
        },
    }
    return json.dumps(site, indent=2)


def on_post_page(output: str, **kwargs) -> str:
    if "application/ld+json" in output:
        return output
    jsonld = _build_jsonld()
    script = (
        '<script type="application/ld+json">\n'
        f'{jsonld}\n'
        '</script>\n'
    )
    if "</head>" in output:
        output = output.replace("</head>", script + "</head>", 1)
    return output
