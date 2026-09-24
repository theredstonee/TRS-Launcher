#version 120

// TRS Client, module "Colors": same maths as core.render.ColorGrade (colour matrix + vibrance).

uniform sampler2D DiffuseSampler;

uniform vec4 RowR;
uniform vec4 RowG;
uniform vec4 RowB;
uniform float Vibrance;

varying vec2 texCoord;

void main() {
    vec4 src = texture2D(DiffuseSampler, texCoord);
    vec4 h = vec4(src.rgb, 1.0);
    vec3 c = vec3(dot(RowR, h), dot(RowG, h), dot(RowB, h));
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, 1.0 + Vibrance * (1.0 - (mx - mn)));
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
