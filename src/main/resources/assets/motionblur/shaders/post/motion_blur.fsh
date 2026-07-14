#version 330

// Velocity-based reprojection motion blur.
// Adapted from Natural Motion Blur by ItsPasi (LGPL-3.0-only)
// https://github.com/ItsPasi/natural-motionblur-fabric
// NOTE: #version must stay on the very first line — VulkanMod's GLSL
// parser rejects shaders with anything (even comments) before it.

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;

layout(std140) uniform MotionBlurUniforms {
    mat4 mvInverse;
    mat4 projInverse;
    mat4 prevModelView;
    mat4 prevProjection;
    vec3 cameraDelta;
    vec2 view_res;
    float blendFactor;
    int   sampleCount;
    int   blurHand;
    int   fullVelocity;
};

in vec2 texCoord;

layout(location = 0) out vec4 color;

vec3 reproject(vec3 screenPos) {
    vec3 ndc      = screenPos * 2.0 - 1.0;
    vec4 viewPos  = projInverse * vec4(ndc, 1.0);
    vec3 worldPos = (mvInverse * vec4(viewPos.xyz / viewPos.w, 1.0)).xyz + cameraDelta;
    vec4 prevClip = prevProjection * (prevModelView * vec4(worldPos, 1.0));
    return (prevClip.xyz / prevClip.w) * 0.5 + 0.5;
}

vec2 clampLength(vec2 velocity) {
    float lenSq = dot(velocity, velocity);
    return (lenSq > 0.16) ? velocity * (0.4 * inversesqrt(lenSq)) : velocity;
}

float noise(vec2 pos) {
    return fract(52.9829189 * fract(0.06711056 * pos.x + 0.00583715 * pos.y));
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(MainDepthSampler, texel, 0).x;

    // Keep the first-person hand/items sharp unless the user opted in.
    if (blurHand == 0 && depth < 0.56) {
        color = texture(MainSampler, texCoord);
        return;
    }

    // Depth dilation avoids halo seams at silhouette edges.
    float dilatedDepth = depth;
    dilatedDepth = min(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 1,  0), 0).x);
    dilatedDepth = min(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2(-1,  0), 0).x);
    dilatedDepth = min(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 0,  1), 0).x);
    dilatedDepth = min(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 0, -1), 0).x);

    vec2 velFull = texCoord - reproject(vec3(texCoord, dilatedDepth)).xy;
    vec2 velocity;
    if (fullVelocity == 1) {
        // Third person / riding: use the true per-pixel velocity, so the
        // orbited focus point (screen centre) stays sharp.
        velocity = clampLength(velFull);
    } else {
        // First person: project onto the pure camera velocity, so
        // translucent/odd-depth surfaces cannot smear against the camera path.
        vec2 velCamera = texCoord - reproject(vec3(texCoord, 1.0)).xy;
        float camMag   = dot(velCamera, velCamera);
        velocity = clampLength(camMag > 1e-12 ? velCamera * (clamp(dot(velFull, velCamera), 0.0, camMag) / camMag) : vec2(0.0));
    }

    float speed   = length(velocity);
    // Scale sample count with the blurred distance so long streaks stay smooth.
    int   samples = clamp(int(ceil(speed * blendFactor * float(sampleCount))), 4, sampleCount);

    vec2  stepUv       = (blendFactor * velocity) / float(samples);
    float centerOffset = -float(samples) * 0.5;
    vec2  seed         = texCoord * view_res;
    vec3  sum          = vec3(0.0);

    for (int i = 0; i < samples; i++) {
        float fi     = float(i);
        float jitter = noise(seed + vec2(fi, fi * 1.4));
        vec2  pos    = texCoord + (fi + centerOffset + jitter) * stepUv;
        vec3  c      = texture(MainSampler, pos).rgb;
        sum         += c * c;
    }
    color = vec4(sqrt(sum / float(samples)), 1.0);
}
