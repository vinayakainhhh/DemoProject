import DS from 'ember-data';

export default DS.Model.extend({
	dbFields : ["id","title", "start" , "end", "language", "description", "eventType","backgroundColor","borderColor","location"],
	
	title: DS.attr('string'),
	start:DS.attr('date',{defaultValue() { return new Date(); }}),
	end:DS.attr('date',{defaultValue() { return new Date(); }}),
	description: DS.attr('string'),
	eventType:DS.attr('string'),
	backgroundColor:DS.attr('string'),
	borderColor:DS.attr('string'),
	location:DS.attr('string'),
	attendees:DS.attr('string'),
	
	groups:DS.attr( ),
	languagesUI: DS.attr( {
			defaultValue() { return []; }
	})
	
	  
});
