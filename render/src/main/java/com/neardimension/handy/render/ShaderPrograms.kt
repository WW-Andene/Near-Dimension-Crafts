package com.arhand.render

/**
 * GLSL shader sources for OpenGL ES 3.0.
 *
 * PHONG  — equivalent to MeshPhongMaterial + 5 directional lights + torch point light + SSS emissive
 * BASIC  — equivalent to MeshBasicMaterial (subsurface shell, rim shell)
 * LINE   — skeleton lines, wireframe edges
 * POINTS — point cloud with size attenuation
 */
object ShaderPrograms {

    // ─── Phong vertex shader ──────────────────────────────────────────────
    val PHONG_VERT = """
        #version 300 es
        precision highp float;

        in vec3 aPosition;
        in vec3 aNormal;

        uniform mat4 uModel;
        uniform mat4 uView;
        uniform mat4 uProjection;
        uniform mat3 uNormalMatrix;

        out vec3 vWorldPos;
        out vec3 vNormal;

        void main() {
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            vWorldPos = worldPos.xyz;
            vNormal = normalize(uNormalMatrix * aNormal);
            gl_Position = uProjection * uView * worldPos;
        }
    """.trimIndent()

    // ─── Phong fragment shader ────────────────────────────────────────────
    val PHONG_FRAG = """
        #version 300 es
        precision highp float;

        in vec3 vWorldPos;
        in vec3 vNormal;

        uniform vec3  uCameraPos;
        uniform float uTime;
        uniform float uAlpha;
        uniform vec3  uBaseColor;

        // Ambient
        uniform vec3  uAmbientColor;
        uniform float uAmbientIntensity;

        // Key light
        uniform vec3  uKeyDir;
        uniform vec3  uKeyColor;
        uniform float uKeyIntensity;

        // Rim light
        uniform vec3  uRimDir;
        uniform vec3  uRimColor;
        uniform float uRimIntensity;

        // SSS
        uniform vec3  uSSSDir;
        uniform vec3  uSSSColor;
        uniform float uSSSIntensity;

        // Fill light
        uniform vec3  uFillDir;
        uniform vec3  uFillColor;
        uniform float uFillIntensity;

        // Torch (point light)
        uniform vec3  uTorchPos;
        uniform vec3  uTorchColor;
        uniform float uTorchIntensity;
        uniform vec3  uTorchAttenuation; // constant, linear, quadratic
        uniform bool  uTorchOn;

        // Inferred landmark pulse
        uniform bool  uInferred;

        out vec4 fragColor;

        vec3 calcDirectional(vec3 dir, vec3 color, float intensity, vec3 N, vec3 V) {
            vec3 L = normalize(dir);
            float diff = max(dot(N, L), 0.0);
            vec3 H = normalize(L + V);
            float spec = pow(max(dot(N, H), 0.0), 32.0);
            return (diff * color + spec * color * 0.3) * intensity;
        }

        void main() {
            vec3 N = normalize(vNormal);
            vec3 V = normalize(uCameraPos - vWorldPos);

            vec3 color = uBaseColor;

            // Ambient
            vec3 light = uAmbientColor * uAmbientIntensity * color;

            // Directional lights
            light += calcDirectional(uKeyDir,  uKeyColor,  uKeyIntensity,  N, V) * color;
            light += calcDirectional(uRimDir,  uRimColor,  uRimIntensity,  N, V) * color;
            light += calcDirectional(uSSSDir,  uSSSColor,  uSSSIntensity,  N, V) * color;
            light += calcDirectional(uFillDir, uFillColor, uFillIntensity, N, V) * color;

            // Torch point light
            if (uTorchOn) {
                vec3 toTorch = uTorchPos - vWorldPos;
                float dist = length(toTorch);
                vec3 L = normalize(toTorch);
                float atten = 1.0 / (uTorchAttenuation.x
                    + uTorchAttenuation.y * dist
                    + uTorchAttenuation.z * dist * dist);
                float diff = max(dot(N, L), 0.0);
                light += diff * uTorchColor * uTorchIntensity * atten * color;
            }

            // Inferred landmark amber pulse
            if (uInferred) {
                float pulse = 0.5 + 0.5 * sin(uTime * 3.0);
                light = mix(light, vec3(1.0, 0.65, 0.2), 0.4 * pulse);
            }

            fragColor = vec4(light, uAlpha);
        }
    """.trimIndent()

    // ─── Basic vertex shader ──────────────────────────────────────────────
    val BASIC_VERT = """
        #version 300 es
        precision highp float;
        in vec3 aPosition;
        uniform mat4 uModel;
        uniform mat4 uView;
        uniform mat4 uProjection;
        void main() {
            gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    val BASIC_FRAG = """
        #version 300 es
        precision highp float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() { fragColor = uColor; }
    """.trimIndent()

    // ─── Line vertex shader ───────────────────────────────────────────────
    val LINE_VERT = BASIC_VERT

    val LINE_FRAG = """
        #version 300 es
        precision highp float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() { fragColor = uColor; }
    """.trimIndent()

    // ─── Point cloud shaders ──────────────────────────────────────────────
    val POINTS_VERT = """
        #version 300 es
        precision highp float;
        in vec3 aPosition;
        uniform mat4 uModel;
        uniform mat4 uView;
        uniform mat4 uProjection;
        uniform float uPointSize;
        void main() {
            vec4 clip = uProjection * uView * uModel * vec4(aPosition, 1.0);
            gl_PointSize = uPointSize / clip.w;
            gl_Position = clip;
        }
    """.trimIndent()

    val POINTS_FRAG = """
        #version 300 es
        precision highp float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            // Circular point
            vec2 coord = gl_PointCoord - vec2(0.5);
            if (dot(coord, coord) > 0.25) discard;
            fragColor = uColor;
        }
    """.trimIndent()

    // ─── Technical HUD skeleton shaders ─────────────────────────────────────
    // Matches a mocap-telemetry reference look: small thin "x" cross joint
    // markers and dotted/dashed connector lines, muted pale cyan-gray rather
    // than a bright neon glow. Kept separate from POINTS_FRAG/LINE_FRAG so the
    // dense point-cloud/depth-cloud renderers keep their plain circular/flat
    // look; this is specifically for HandRenderer/BodySkeletonRenderer/
    // FaceSkeletonRenderer's SKELETON mode.

    /** Reuses POINTS_VERT — only the fragment stage changes. */
    val CYBER_POINT_FRAG = """
        #version 300 es
        precision highp float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            vec2 c = gl_PointCoord - vec2(0.5);
            if (dot(c, c) > 0.25) discard;
            // Thin "x" cross: two diagonal bars through the point centre
            float d1 = abs(c.x - c.y);
            float d2 = abs(c.x + c.y);
            if (min(d1, d2) > 0.09) discard;
            fragColor = uColor;
        }
    """.trimIndent()

    // ─── Dashed/dotted connector line ───────────────────────────────────────
    // Adds a per-vertex "distance travelled along this segment" attribute so
    // the fragment stage can cut the line into dots/dashes — a plain solid
    // LINE_VERT/FRAG has no way to know where it is along the line's length.

    val DASH_LINE_VERT = """
        #version 300 es
        precision highp float;
        in vec3 aPosition;
        in float aDist;
        uniform mat4 uModel;
        uniform mat4 uView;
        uniform mat4 uProjection;
        out float vDist;
        void main() {
            vDist = aDist;
            gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    val DASH_LINE_FRAG = """
        #version 300 es
        precision highp float;
        uniform vec4  uColor;
        uniform float uDashSize;
        in float vDist;
        out vec4 fragColor;
        void main() {
            float cycle = fract(vDist / uDashSize);
            if (cycle > 0.5) discard;
            fragColor = uColor;
        }
    """.trimIndent()

    // ─── Camera passthrough shaders ───────────────────────────────────────
    // Draws the camera Bitmap as a fullscreen background quad.
    // uMirrorX flips U for front-facing camera correction.

    val CAMERA_VERT = """
        #version 300 es
        precision highp float;
        in vec2 aPosition;
        in vec2 aUV;
        uniform int   uMirrorX;
        uniform float uCropU;
        uniform float uCropV;
        out vec2 vUV;
        void main() {
            float u = uCropU + aUV.x * (1.0 - 2.0 * uCropU);
            float v = uCropV + aUV.y * (1.0 - 2.0 * uCropV);
            if (uMirrorX == 1) u = 1.0 - u;
            vUV = vec2(u, v);
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """.trimIndent()

    val CAMERA_FRAG = """
        #version 300 es
        precision mediump float;
        uniform sampler2D uTexture;
        in vec2 vUV;
        out vec4 fragColor;
        void main() {
            fragColor = texture(uTexture, vUV);
        }
    """.trimIndent()
}
