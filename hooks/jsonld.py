import json


def _build_jsonld() -> str:
    site = {
        "@context": "https://schema.org",
        "@type": "WebSite",
        "name": "JNode",
        "url": "https://lsantha.github.io/jnode_ai/",
        "description": "JNode is an experimental operating system written in Java. It builds its own JVM, kernel, drivers, filesystems, network stack, and GUI, and boots them on bare metal.",
        "logo": "https://lsantha.github.io/jnode_ai/assets/images/JNode_logo.png",
        "publisher": {
            "@type": "Organization",
            "name": "JNode Project",
            "url": "https://github.com/LSantha/jnode_ai",
            "sameAs": [
                "https://github.com/LSantha/jnode_ai",
                "https://github.com/LSantha/jnode_ai/wiki",
            ],
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
