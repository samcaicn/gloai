/**
 * T-logo — neon "T" mark rendered as inline SVG.
 *
 * Replaces the legacy 3D cube. Same prop surface (size / className /
 * showParticles / variant) so callers keep working untouched.
 *
 * Idle:    slow pulse + soft glow drift.
 * Compact: drops particles and ring for tiny sizes.
 */

import React, { useMemo, useId } from 'react';
import './CubeLogo.scss';

export type CubeLogoVariant = 'default' | 'compact';

export interface CubeLogoProps {
  /** Logo edge length (px) */
  size?: number;
  /** Custom class name */
  className?: string;
  /** Whether to show particle effects */
  showParticles?: boolean;
  /** Variant: default - full | compact - compact (for small sizes) */
  variant?: CubeLogoVariant;
}

interface Particle {
  id: number;
  x: number;
  y: number;
  size: number;
  duration: number;
  delay: number;
  opacity: number;
}

const T_PATH =
  'M 200 256 L 824 256 L 824 392 L 612 392 L 612 824 L 412 824 L 412 392 L 200 392 Z';

export const CubeLogo: React.FC<CubeLogoProps> = ({
  size = 100,
  className = '',
  showParticles = true,
  variant = 'default',
}) => {
  const effectiveVariant = variant === 'default' && size < 50 ? 'compact' : variant;
  const isCompact = effectiveVariant === 'compact';
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const gradId = `t-grad-${uid}`;
  const ringGradId = `t-ring-${uid}`;
  const glowId = `t-glow-${uid}`;
  const softGlowId = `t-soft-${uid}`;

  const particles = useMemo<Particle[]>(() => {
    if (!showParticles || isCompact) return [];
    const count = 10;
    return Array.from({ length: count }, (_, i) => ({
      id: i,
      x: Math.random() * 140 - 20,
      y: Math.random() * 140 - 20,
      size: Math.random() * 3 + 1,
      duration: Math.random() * 3 + 4,
      delay: Math.random() * 4,
      opacity: Math.random() * 0.5 + 0.2,
    }));
  }, [showParticles, isCompact]);

  return (
    <div
      className={`cube-logo ${isCompact ? 'cube-logo--compact' : ''} ${className}`}
      style={{ width: size, height: size }}
      role="img"
      aria-label="tupai logo"
    >
      {particles.length > 0 && (
        <div className="cube-logo__particles">
          {particles.map((p) => (
            <span
              key={p.id}
              className="cube-logo__particle"
              style={{
                left: `${p.x}%`,
                top: `${p.y}%`,
                width: p.size,
                height: p.size,
                opacity: p.opacity,
                animationDuration: `${p.duration}s`,
                animationDelay: `${p.delay}s`,
              }}
            />
          ))}
        </div>
      )}

      <svg
        className="cube-logo__svg"
        viewBox="0 0 1024 1024"
        width="100%"
        height="100%"
        aria-hidden="true"
        focusable="false"
      >
        <defs>
          <linearGradient id={gradId} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#00f0ff" />
            <stop offset="55%" stopColor="#22d3ee" />
            <stop offset="100%" stopColor="#a855f7" />
          </linearGradient>
          <linearGradient id={ringGradId} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#00f0ff" stopOpacity="0.9" />
            <stop offset="100%" stopColor="#a855f7" stopOpacity="0.9" />
          </linearGradient>
          <filter id={glowId} x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur stdDeviation="14" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          <filter id={softGlowId} x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="22" result="b1" />
            <feMerge>
              <feMergeNode in="b1" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        <rect
          className="cube-logo__bg"
          x="0"
          y="0"
          width="1024"
          height="1024"
          rx="224"
          ry="224"
        />
        {!isCompact && (
          <rect
            className="cube-logo__ring"
            x="48"
            y="48"
            width="928"
            height="928"
            rx="190"
            ry="190"
            fill="none"
            stroke={`url(#${ringGradId})`}
            strokeWidth="3"
            opacity="0.55"
          />
        )}
        <g filter={`url(#${glowId})`}>
          <path
            className="cube-logo__t"
            d={T_PATH}
            fill={`url(#${gradId})`}
          />
        </g>
        <path
          className="cube-logo__t-stroke"
          d={T_PATH}
          fill="none"
          stroke="#e6fcff"
          strokeWidth="3"
          opacity="0.55"
        />
        {!isCompact && (
          <rect
            className="cube-logo__accent-top"
            x="216"
            y="226"
            width="592"
            height="6"
            rx="3"
            fill="#00f0ff"
            opacity="0.7"
            filter={`url(#${softGlowId})`}
          />
        )}
        <rect
          className="cube-logo__spine"
          x="503"
          y="412"
          width="18"
          height="382"
          rx="9"
          fill="#f0fdff"
          opacity="0.42"
        />
        <ellipse
          cx="512"
          cy="852"
          rx="220"
          ry="14"
          fill="#000000"
          opacity="0.45"
        />
      </svg>
    </div>
  );
};

CubeLogo.displayName = 'CubeLogo';

export default CubeLogo;
