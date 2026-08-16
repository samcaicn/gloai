import { useCallback, useState } from 'react';
import { Star } from 'lucide-react';
import { useStarRatingStore } from '@/flow_chat/store/starRatingStore';
import './SkillStarRating.scss';

export function SkillStarRating() {
  const pending = useStarRatingStore((s) => s.pending);
  const submitting = useStarRatingStore((s) => s.submitting);
  const submitRating = useStarRatingStore((s) => s.submitRating);
  const dismiss = useStarRatingStore((s) => s.dismiss);

  const [hovered, setHovered] = useState(0);
  const [selected, setSelected] = useState(0);

  const handleClick = useCallback(
    (stars: number) => {
      setSelected(stars);
      void submitRating(stars);
    },
    [submitRating],
  );

  const handleDismiss = useCallback(() => {
    setSelected(0);
    setHovered(0);
    dismiss();
  }, [dismiss]);

  if (!pending) return null;

  return (
    <div className="star-rating-overlay" onClick={handleDismiss}>
      <div className="star-rating-card" onClick={(e) => e.stopPropagation()}>
        <div className="star-rating-header">
          <span className="star-rating-title">评价技能执行</span>
          <button type="button" className="star-rating-close" onClick={handleDismiss}>
            ✕
          </button>
        </div>
        <div className="star-rating-skill-name">{pending.skillName || pending.skillId}</div>
        <div className="star-rating-hint">请为本次执行结果评分</div>
        <div className="star-rating-stars">
          {[1, 2, 3, 4, 5].map((star) => {
            const active = star <= (hovered || selected);
            return (
              <button
                key={star}
                type="button"
                className={`star-rating-star${active ? ' star-rating-star--active' : ''}`}
                onMouseEnter={() => setHovered(star)}
                onMouseLeave={() => setHovered(0)}
                onClick={() => handleClick(star)}
                disabled={submitting}
                aria-label={`${star} 星`}
              >
                <Star size={24} fill={active ? 'currentColor' : 'none'} />
              </button>
            );
          })}
        </div>
        {submitting && <div className="star-rating-submitting">提交中...</div>}
      </div>
    </div>
  );
}
