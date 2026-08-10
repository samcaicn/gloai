/* Hamburger menu toggle */
document.addEventListener('DOMContentLoaded', function(){
  var burger = document.querySelector('.nav-burger');
  var nav = document.querySelector('.nav');
  if(burger && nav) burger.addEventListener('click', function(){ nav.classList.toggle('menu-open'); });
});

/* Modal helpers */
function openModal(id){
  var el = document.getElementById(id);
  if(!el) return;
  el.classList.add('open');
  document.body.style.overflow = 'hidden';
  if(window.__runCounters) window.__runCounters(el);
}
function closeModal(id){
  var el = document.getElementById(id);
  if(!el) return;
  el.classList.remove('open');
  document.body.style.overflow = '';
}
function openContact(){ openModal('contactModal'); }

/* Close modal on overlay click + Escape */
document.addEventListener('DOMContentLoaded', function(){
  document.querySelectorAll('.modal-overlay').forEach(function(o){
    o.addEventListener('click', function(e){ if(e.target === o) closeModal(o.id); });
  });
  document.addEventListener('keydown', function(e){
    if(e.key === 'Escape') document.querySelectorAll('.modal-overlay.open').forEach(function(m){ closeModal(m.id); });
  });
});
